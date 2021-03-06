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
import br.com.dafiti.hanger.model.JobParent;
import br.com.dafiti.hanger.model.Server;
import br.com.dafiti.hanger.model.Subject;
import br.com.dafiti.hanger.model.User;
import br.com.dafiti.hanger.option.Flow;
import br.com.dafiti.hanger.option.Scope;
import br.com.dafiti.hanger.repository.JobRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
public class JobService {

    private final JobRepository jobRepository;
    private final JobParentService jobParentService;
    private final JenkinsService jenkinsService;
    private final JobStatusService jobStatusService;

    @Autowired
    public JobService(
            JobRepository jobRepository,
            JobParentService jobParentService,
            JenkinsService jenkinsService,
            JobStatusService jobStatusService) {

        this.jobRepository = jobRepository;
        this.jobParentService = jobParentService;
        this.jenkinsService = jenkinsService;
        this.jobStatusService = jobStatusService;
    }

    public Iterable<Job> list() {
        return jobRepository.findAll();
    }

    @Cacheable(value = "jobs")
    public Iterable<Job> listFromCache() {
        return jobRepository.findAll();
    }

    public Job load(Long id) {
        return jobRepository.findOne(id);
    }

    public List<Job> findBySubjectOrderByName(Subject subject) {
        return jobRepository.findBySubjectOrderByName(subject);
    }

    public HashSet<Job> findByApprover(User user) {
        return this.jobRepository.findByApprover(user);
    }

    public Job findByName(String name) {
        return jobRepository.findByName(name);
    }

    public List<Job> findByNameContainingOrAliasContaining(String search) {
        return jobRepository.findByNameContainingOrAliasContaining(search,search);
    }

    public Job save(Job job) {
        return jobRepository.save(job);
    }

    @Caching(evict = {
        @CacheEvict(value = "jobs", allEntries = true)})
    public Job saveAndRefreshCache(Job job) {
        return jobRepository.save(job);
    }

    @Caching(evict = {
        @CacheEvict(value = "jobs", allEntries = true)})
    public void delete(Long id) {
        jobRepository.delete(id);
    }

    @Caching(evict = {
        @CacheEvict(value = "jobs", allEntries = true)})
    public void refresh() {
    }

    /**
     * Rebuild mesh.
     *
     * @param job Job
     */
    public void rebuildMesh(Job job) {
        job.getParent().stream().forEach((jobParent) -> {
            this.rebuildMesh(jobParent.getParent());
        });

        jobStatusService.updateFlow(job.getStatus(), Flow.REBUILD);
    }

    /**
     * Add a job parent.
     *
     * @param job Job
     * @param server Server
     * @param parentJobList ParentJobList
     * @param parentUpstream ParentUpstream
     * @throws Exception
     */
    public void addParent(
            Job job,
            Server server,
            List<String> parentJobList,
            boolean parentUpstream) throws Exception {

        if (parentJobList == null) {
            parentJobList = new ArrayList();
        }

        //Identify if upstream jobs should be imported to Hanger. 
        if (parentUpstream) {
            List<String> upstreamJobs = jenkinsService.getUpstreamProjects(job);

            if (upstreamJobs.size() > 0) {
                for (String upstreamName : upstreamJobs) {
                    parentJobList.add(upstreamName);
                }
            }
        }

        for (String name : parentJobList) {
            StringBuilder lineage = new StringBuilder();
            Job parentJob = this.findByName(name);

            //Identify if the parent is valid.
            if (!job.getName().equals(name) && !name.toUpperCase().contains("_TRIGGER_")) {
                //Identify if the parent job should be imported. 
                if (parentJob == null) {
                    Job transientJob = new Job(name, server);

                    //Identify if should import upstream jobs recursively.
                    if (parentUpstream) {
                        this.addParent(transientJob, server, null, parentUpstream);
                    }

                    //Import the parent job. 
                    parentJob = this.save(transientJob);
                    jenkinsService.updateJob(parentJob);
                }

                //Identify if there are ciclic reference in the flow. 
                if (!this.hasCyclicReference(job, parentJob, lineage)) {
                    boolean addParent = true;

                    for (JobParent parent : job.getParent()) {
                        addParent = !parent.getParent().equals(parentJob);

                        if (!addParent) {
                            break;
                        }
                    }

                    //Define the relation between a job and it parents.
                    if (addParent) {
                        job.addParent(new JobParent(job, parentJob, Scope.FULL));
                    }
                } else {
                    throw new Exception("Cyclic Reference: " + lineage.toString().concat(parentJob.getName()));
                }
            }
        }
    }

    /**
     * Add a job checkup trigger.
     *
     * @param job Job
     * @param checkupIndex checkupIndex
     * @param triggers triggers
     * @throws Exception
     */
    public void addTrigger(
            Job job,
            int checkupIndex,
            List<String> triggers) throws Exception {

        if (triggers == null) {
            triggers = new ArrayList();
        }

        for (String name : triggers) {
            Job triggeredJob = this.findByName(name);

            if (triggeredJob != null) {
                //Identify if the job has checkup.  
                if (job.getCheckup().size() >= checkupIndex) {
                    //Identify if the trigger was already add. 
                    if (!job.getCheckup().get(checkupIndex).getTrigger().contains(triggeredJob)) {
                        //Add the trigger. 
                        job.getCheckup().get(checkupIndex).addTrigger(triggeredJob);
                    }
                }
            }
        }
    }

    /**
     * Identify ciclic Reference.
     *
     * @param job Job
     * @param parent Job
     * @param lineage lineage
     * @return Identify ciclic Reference
     */
    public boolean hasCyclicReference(
            Job job,
            Job parent,
            StringBuilder lineage) {

        return hasCyclicReference(job, parent, false, lineage);
    }

    /**
     * Identify ciclic Reference recursively.
     *
     * @param job Job
     * @param parent Job
     * @param stop stop
     * @param lineage lineage
     * @return Identify ciclic Reference
     */
    private boolean hasCyclicReference(
            Job job,
            Job parent,
            boolean stop,
            StringBuilder lineage) {

        if (!stop) {
            stop = parent.equals(job);
            lineage.append(parent.getName()).append(" < ");

            for (JobParent jobParent : parent.getParent()) {
                stop = this.hasCyclicReference(job, jobParent.getParent(), stop, lineage);
            }
        }

        return stop;
    }

    /**
     * Identify relation path.
     *
     * @param jobTo
     * @param jobFrom
     * @return
     */
    public HashSet<Job> getRelationPath(
            Job jobTo,
            Job jobFrom) {

        return this.getRelationPath(jobTo, jobFrom, new HashSet(), "");
    }

    /**
     * Identify relation path recursively.
     *
     * @param jobTo Job
     * @param jobFrom Job
     * @param lineage lineage
     * @param path path
     * @param parent Job
     * @return Identify relation path recursively
     */
    private HashSet<Job> getRelationPath(
            Job jobTo,
            Job jobFrom,
            HashSet<Job> path,
            String lineage) {

        if (lineage.isEmpty()) {
            lineage += jobTo.getId();
        } else {
            lineage += "," + jobTo.getId();
        }

        for (JobParent jobParent : jobTo.getParent()) {
            getRelationPath(jobParent.getParent(), jobFrom, path, lineage);
        }

        //Identify if the job target was found. 
        if (jobTo.equals(jobFrom)) {
            List<String> jobPath = Arrays.asList(lineage.split(","));

            for (String jobId : jobPath) {
                Job jobFound = this.load(Long.valueOf(jobId));

                if (jobFound != null) {
                    path.add(jobFound);
                }
            }
        }

        return path;
    }

    /**
     * Identify mesh.
     *
     * @param job Job
     * @param self self
     * @return Identify mesh
     */
    public HashSet<Job> getMesh(
            Job job,
            boolean self) {

        HashSet<Job> mesh = this.getMesh(job, new HashSet());

        if (!self) {
            mesh.remove(job);
        }

        return mesh;
    }

    /**
     * Identify mesh recursively.
     *
     * @param job Job
     * @param mesh Mesh list
     * @return Mesh list.
     */
    private HashSet<Job> getMesh(
            Job job,
            HashSet<Job> mesh) {

        job.getParent().stream().forEach((jobParent) -> {
            this.getMesh(jobParent.getParent(), mesh);
        });

        mesh.add(job);

        return mesh;
    }

    /**
     * Identify mesh parent.
     *
     * @param job Job
     * @return Return the mesh parent list.
     */
    public HashSet<Job> getMeshParent(Job job) {
        return this.getMeshParent(job, new HashSet());
    }

    /**
     * Identify mesh parent recursively.
     *
     * @param job Job
     * @param meshParent Mesh parent list
     * @return Return the mesh parent list.
     */
    private HashSet<Job> getMeshParent(
            Job job,
            HashSet<Job> meshParent) {

        job.getParent().stream().forEach((jobParent) -> {
            this.getMeshParent(jobParent.getParent(), meshParent);
        });

        if (job.getParent().isEmpty()) {
            meshParent.add(job);
        }

        return meshParent;
    }

    /**
     * Identify propagation.
     *
     * @param job Job
     * @param self self
     * @return Identify mesh
     */
    public HashSet<Job> getPropagation(
            Job job,
            boolean self) {

        HashSet<Job> propagation = this.getPropagation(job, new HashSet());

        if (!self) {
            propagation.remove(job);
        }

        return propagation;
    }

    /**
     * Identify propagation recursively.
     *
     * @param job Job
     * @param childJob Job
     * @param propagation Propagation list
     * @return Mesh list.
     */
    private HashSet<Job> getPropagation(
            Job job,
            HashSet<Job> propagation) {

        HashSet<JobParent> childs = jobParentService.findByParent(job);

        if (!childs.isEmpty()) {
            childs.stream().forEach((child) -> {
                this.getPropagation(child.getJob(), propagation);
            });
        }

        propagation.add(job);
        return propagation;
    }
}
