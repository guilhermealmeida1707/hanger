/*
 * Copyright (c) 2018 Dafiti Group
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * 
 */
package br.com.dafiti.hanger.service;

import br.com.dafiti.hanger.model.Job;
import br.com.dafiti.hanger.model.Server;
import com.offbytwo.jenkins.JenkinsServer;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

/**
 *
 * @author Valdiney V GOMES
 */
@Service
public class JenkinsService {

    private final HttpServletRequest request;

    @Autowired
    public JenkinsService(HttpServletRequest request) {
        this.request = request;
    }

    /**
     * Get a Jenkins server connection.
     *
     * @param server Server Server
     * @return Server connection
     * @throws URISyntaxException
     */
    public JenkinsServer getJenkinsServer(Server server) throws URISyntaxException {
        JenkinsServer jenkins = null;

        if (server != null) {
            jenkins = new JenkinsServer(new URI(
                    server.getUrl()),
                    server.getUsername(),
                    server.getToken());
        }

        return jenkins;
    }

    /**
     * Test if a server is running.
     *
     * @param server Server Server
     * @return Identify if a job is running.
     */
    public boolean isRunning(Server server) {
        boolean running = false;
        JenkinsServer jenkins;
        try {
            jenkins = this.getJenkinsServer(server);

            if (jenkins != null) {
                running = (jenkins.isRunning());
                jenkins.close();
            }
        } catch (URISyntaxException ex) {
            Logger.getLogger(JenkinsService.class.getName()).log(Level.SEVERE, "Fail checking if a server is running!", ex);
        }

        return running;
    }

    /**
     * Get Jenkins job list.
     *
     * @param server Server
     * @return Job list
     * @throws URISyntaxException
     * @throws IOException
     */
    @Cacheable(value = "serverJobs", key = "#server.id")
    public List<String> listJob(Server server) throws URISyntaxException, IOException {
        List<String> jobs = new ArrayList();
        JenkinsServer jenkins = this.getJenkinsServer(server);

        if (jenkins != null) {
            if (jenkins.isRunning()) {
                for (String job : jenkins.getJobs().keySet()) {
                    jobs.add(job);
                }
            }

            jenkins.close();
        }

        return jobs;
    }

    /**
     * Build a Jenkins job.
     *
     * @param job Job
     * @return Identify if job was built.
     * @throws URISyntaxException
     * @throws IOException
     */
    public boolean build(Job job) throws URISyntaxException, IOException {
        JenkinsServer jenkins;
        boolean built = false;

        if (job != null) {
            jenkins = this.getJenkinsServer(job.getServer());

            if (jenkins != null) {
                if (jenkins.isRunning()) {
                    try {
                        built = (jenkins.getJob(job.getName()).build(true) != null);
                    } catch (IOException buildException) {
                        Logger.getLogger(JenkinsService.class.getName()).log(Level.SEVERE, "Fail building job: " + job.getName(), buildException);
                        try {
                            built = (jenkins.getJob(job.getName()).build(new HashMap(), true) != null);
                        } catch (IOException ex) {
                            Logger.getLogger(JenkinsService.class.getName()).log(Level.SEVERE, "Fail building parametrized job!" + job.getName(), buildException);
                            throw buildException;
                        }
                    } finally {
                        jenkins.close();
                    }
                }
            }
        }

        return built;
    }

    /**
     * Get a Jenkins job upstream project list.
     *
     * @param job Job
     * @return Upstream project list
     */
    public List<String> getUpstreamProjects(Job job) {
        JenkinsServer jenkins;
        List<String> upstreamProjects = new ArrayList();

        if (job != null) {
            try {
                jenkins = this.getJenkinsServer(job.getServer());

                if (jenkins != null) {
                    if (jenkins.isRunning()) {
                        jenkins.getJob(job.getName()).getUpstreamProjects().stream().forEach((upstream) -> {
                            upstreamProjects.add(upstream.getName());
                        });
                    }

                    jenkins.close();
                }
            } catch (URISyntaxException | IOException ex) {
                Logger.getLogger(JenkinsService.class.getName()).log(Level.SEVERE, "Fail getting upstream projects!", ex);
            }
        }

        return upstreamProjects;
    }

    /**
     * Identify if a job is in queue.
     *
     * @param job Job
     * @return Identify if a job is in queue
     */
    public boolean isInQueue(Job job) {
        JenkinsServer jenkins;
        boolean isInQueue = false;

        if (job != null) {
            try {
                jenkins = this.getJenkinsServer(job.getServer());

                if (jenkins != null) {
                    if (jenkins.isRunning()) {
                        isInQueue = jenkins.getJob(job.getName()).isInQueue();
                    }

                    jenkins.close();
                }
            } catch (URISyntaxException | IOException ex) {
                Logger.getLogger(JenkinsService.class.getName()).log(Level.SEVERE, "Fail identifying if a job is in queue!", ex);
            }
        }

        return isInQueue;
    }

    /**
     * Rename a job.
     *
     * @param job Job
     * @param oldName Name
     */
    public void renameJob(Job job, String oldName) {
        JenkinsServer jenkins;

        if (job != null) {
            try {
                jenkins = this.getJenkinsServer(job.getServer());

                if (jenkins != null) {
                    if (jenkins.isRunning()) {
                        jenkins.renameJob(oldName, job.getName(), true);
                    }

                    jenkins.close();
                }
            } catch (URISyntaxException | IOException ex) {
                Logger.getLogger(JenkinsService.class.getName()).log(Level.SEVERE, "Fail renaming a job!", ex);
            }
        }
    }

    /**
     * Add the Hanger endpoint to the notificion plugin configuration of a
     * Jenkins job.
     *
     * @param job Job
     */
    public void updateJob(Job job) {
        JenkinsServer jenkins;

        if (job != null) {
            try {
                jenkins = this.getJenkinsServer(job.getServer());

                if (jenkins != null) {
                    if (jenkins.isRunning()) {
                        String name = job.getName();
                        String config = jenkins.getJobXml(name);
                        String description = jenkins.getJob(name).getDescription();
                        String url = request.getRequestURL().toString().replace(request.getRequestURI(), request.getContextPath());

                        //Identify if Notification plugin is configured. 
                        if (!config.contains("com.tikal.hudson.plugins.notification.HudsonNotificationProperty")) {
                            String notificationPluginConfig = ""
                                    + "    <com.tikal.hudson.plugins.notification.HudsonNotificationProperty plugin=\"notification@1.12\">\n"
                                    + "      <endpoints>\n"
                                    + "        <com.tikal.hudson.plugins.notification.Endpoint>\n"
                                    + "          <protocol>HTTP</protocol>\n"
                                    + "          <format>JSON</format>\n"
                                    + "          <urlInfo>\n"
                                    + "            <urlOrId>" + url + "/observer</urlOrId>\n"
                                    + "            <urlType>PUBLIC</urlType>\n"
                                    + "          </urlInfo>\n"
                                    + "          <event>all</event>\n"
                                    + "          <timeout>30000</timeout>\n"
                                    + "          <loglines>0</loglines>\n"
                                    + "          <retries>3</retries>\n"
                                    + "        </com.tikal.hudson.plugins.notification.Endpoint>\n"
                                    + "      </endpoints>\n"
                                    + "    </com.tikal.hudson.plugins.notification.HudsonNotificationProperty>\n";

                            //Add notification plugin tags to Jenkins config XML. 
                            if (config.contains("<properties>")) {
                                config = config.replace("</properties>", notificationPluginConfig + "</properties>");
                            } else {
                                config = config.replace("<properties/>", "<properties>\n" + notificationPluginConfig + "</properties>");
                            }
                        } else if (config.contains("<com.tikal.hudson.plugins.notification.Endpoint>")) {
                            String notificationPluginEndpoint = ""
                                    + "        <com.tikal.hudson.plugins.notification.Endpoint>\n"
                                    + "          <protocol>HTTP</protocol>\n"
                                    + "          <format>JSON</format>\n"
                                    + "          <urlInfo>\n"
                                    + "            <urlOrId>" + url + "/observer</urlOrId>\n"
                                    + "            <urlType>PUBLIC</urlType>\n"
                                    + "          </urlInfo>\n"
                                    + "          <event>all</event>\n"
                                    + "          <timeout>30000</timeout>\n"
                                    + "          <loglines>0</loglines>\n"
                                    + "          <retries>3</retries>\n"
                                    + "        </com.tikal.hudson.plugins.notification.Endpoint>\n";

                            //Replace notification plugin endpoint tag of Jenkins config XML
                            config = config.replaceAll("(?s)<com\\.tikal\\.hudson\\.plugins\\.notification\\.Endpoint>(.*)</com\\.tikal\\.hudson\\.plugins\\.notification\\.Endpoint>", notificationPluginEndpoint);
                        }

                        //Update Jenkins job. 
                        jenkins.updateJob(name, config, true);

                        //Build Jenkins job description. 
                        description = description == null ? "" : description;
                        description = description.replaceAll("(?s)NICK\\.BEGIN(.*)NICK\\.END", "");
                        description = description.replaceAll("(?s)HANGER\\.BEGIN(.*)HANGER\\.END", "");
                        description = description + "\n" + "HANGER.BEGIN" + job.toString() + "HANGER.END";

                        //Update Jenkins job. 
                        jenkins.getJob(name).updateDescription(description.trim(), true);
                    }

                    jenkins.close();
                }
            } catch (URISyntaxException | IOException ex) {
                Logger.getLogger(JenkinsService.class.getName()).log(Level.WARNING, "Fail updating Jenkins job!", ex);
            }
        }
    }

    /**
     * Identify if notification plugin is deployed.
     *
     * @param server Server
     * @return Identify if notification plugin is deployed.
     */
    public boolean hasNotificationPlugin(Server server) {
        boolean notification = false;

        try {
            JenkinsServer jenkins = this.getJenkinsServer(server);

            if (jenkins != null) {
                if (jenkins.isRunning()) {
                    notification = jenkins
                            .getPluginManager()
                            .getPlugins()
                            .stream()
                            .filter(x -> x.getShortName().equals("notification"))
                            .count() == 1;
                }

                jenkins.close();
            }
        } catch (URISyntaxException | IOException ex) {
            Logger.getLogger(JenkinsService.class.getName()).log(Level.SEVERE, "Fail getting plugin list!", ex);
        }

        return notification;
    }

    /**
     * Clean the job list cache.
     */
    @Caching(evict = {
        @CacheEvict(value = "serverJobs", allEntries = true)})
    public void refreshCache() {
    }
}
