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
import br.com.dafiti.hanger.model.JobBuild;
import br.com.dafiti.hanger.model.JobStatus;
import br.com.dafiti.hanger.option.Flow;
import br.com.dafiti.hanger.option.Phase;
import br.com.dafiti.hanger.option.Scope;
import br.com.dafiti.hanger.option.Status;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.LocalDate;
import org.joda.time.Minutes;
import org.springframework.stereotype.Service;

/**
 *
 * @author Valdiney V GOMES
 */
@Service
public class JobBuildStatusService {

    /**
     * Identify if a parent is built.
     *
     * @param job Job job
     * @return Identify if a job parent is built
     */
    public boolean isBuilt(Job job) {
        boolean built;

        //Get the status of each parent.
        JobStatus jobStatus = job.getStatus();

        //Identify if the job was built at least once a time.
        built = (jobStatus != null);

        if (built) {
            JobBuild jobBuild = jobStatus.getBuild();

            //Identify if the job was built sucessfully.
            built = (jobBuild != null);

            if (built) {
                //Identify if the job was built today.
                built = Days.daysBetween(new LocalDate(jobBuild.getDate()), new LocalDate()).getDays() == 0;

                if (!built) {
                    //Identify if it has antecipation tolerance.
                    if (job.getTolerance() != 0) {
                        //Identify if the job was built in the antecipation tolerance interval today.
                        built = Days.daysBetween(new LocalDate(
                                new DateTime(jobBuild.getDate()).plusHours(job.getTolerance())),
                                new LocalDate()).getDays() == 0;
                    }
                }

                if (built) {
                    //Identify if the job build is finalized and successfully. 
                    built = (jobBuild.getPhase().equals(Phase.FINALIZED)
                            && jobBuild.getStatus().equals(Status.SUCCESS)
                            && jobStatus.getScope() == Scope.FULL
                            && (jobStatus.getFlow().equals(Flow.NORMAL) || jobStatus.getFlow().equals(Flow.APPROVED)));
                }
            }
        }

        return built;
    }

    /**
     * Identify if can build a job.
     *
     * @param job Job
     * @return Identify if can build a job
     */
    public boolean isBuildable(Job job) {
        boolean buildable = job.isEnabled();

        if (buildable) {
            //Get the status of each child.
            JobStatus jobStatus = job.getStatus();

            //Identify if the job has status.
            buildable = (jobStatus == null);

            if (!buildable) {
                JobBuild jobBuild = jobStatus.getBuild();

                //Identify if the job was never trigger.
                buildable = (jobBuild == null);

                if (!buildable) {
                    //Identify if the job was not built today.
                    buildable = Days.daysBetween(new LocalDate(jobBuild.getDate()), new LocalDate()).getDays() != 0;

                    if (!buildable) {
                        //Identify if the job was not built in the antecipation tolerance interval today.
                        if (job.getTolerance() != 0) {
                            buildable = Days.daysBetween(
                                    new LocalDate(new DateTime(jobBuild.getDate()).plusHours(job.getTolerance())),
                                    new LocalDate()).getDays() != 0;
                        }
                    }
                }

                if (!buildable) {
                    //Identify if the job was not built fully.
                    buildable = (jobStatus.getScope() == Scope.PARTIAL);

                    if (!buildable) {
                        //Identify if the job can be rebuilt along the day. 
                        buildable = job.isRebuild();

                        if (buildable) {
                            //Identify if it is in waiting time.
                            if (job.getWait() != 0) {
                                buildable = Minutes.minutesBetween(new DateTime(
                                        jobBuild.getDate()),
                                        new DateTime()).getMinutes() >= job.getWait();
                            }
                        }
                    }
                }

                if (!buildable) {
                    //Identify if the job has any problem or is a rebuild mesh.
                    buildable = jobStatus.getFlow().equals(Flow.ERROR)
                            || jobStatus.getFlow().equals(Flow.REBUILD);
                }
            }
        }

        return buildable;
    }
}
