/*
 * The MIT License
 * 
 * Copyright (c) 2004-2016, Sun Microsystems, Inc., Kohsuke Kawaguchi, Martin Eigenbrodt
 *                          SAP SE
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.sap.jenkins.plugins.upstreamkeeplogrotator;

import com.google.common.collect.Lists;
import hudson.Extension;
import hudson.model.Cause;
import hudson.model.Job;
import hudson.model.Run;
import jenkins.model.BuildDiscarder;
import jenkins.model.BuildDiscarderDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.*;

/**
 * Default implementation of {@link BuildDiscarder}.
 *
 * For historical reason, this is called LogRotator, but it does not rotate logs :-)
 * 
 * Since 1.350 it has also the option to keep the build, but delete its recorded artifacts.
 * 
 * @author Kohsuke Kawaguchi
 */
public class LogRotator extends BuildDiscarder {

    /**
     * If not -1, history is only kept up to this days.
     */
    private final int daysToKeep;

    /**
     * If not -1, only this number of build logs are kept.
     */
    private final int numToKeep;

    /**
     * If not -1 nor null, artifacts are only kept up to this days.
     * Null handling is necessary to remain data compatible with old versions.
     * @since 1.350
     */
    private final Integer artifactDaysToKeep;

    /**
     * If not -1 nor null, only this number of builds have their artifacts kept.
     * Null handling is necessary to remain data compatible with old versions.
     * @since 1.350
     */
    private final Integer artifactNumToKeep;

    /**
     * If true, builds will not be deleted if their upstream causes still exist.
     */
    private final boolean upstreamKeep;

    /**
     * if true, builds whose upstream causes still exist will not even have their
     * artifacts cleaned up.
     */
    private final boolean upstreamKeepArtifacts;

    /**
     * Record of the current build record cleanup jobs.
     */
    private static final HashSet<Job<?,?>> currentCleanupJobs = new HashSet<Job<?,?>>();

    @DataBoundConstructor
    public LogRotator (String daysToKeepStr, String numToKeepStr, String artifactDaysToKeepStr, String artifactNumToKeepStr, Boolean upstreamKeep, Boolean upstreamKeepArtifacts) {
        this (parse(daysToKeepStr),parse(numToKeepStr),
              parse(artifactDaysToKeepStr),parse(artifactNumToKeepStr),
              parse(upstreamKeep), parse(upstreamKeepArtifacts));
    }

    public static int parse(String p) {
        if(p==null)     return -1;
        try {
            return Integer.parseInt(p);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean parse(Boolean p) {
        if ( p == null ) return false;
        return p;
    }

    /**
     * @deprecated since 1.350.
     *      Use {@link #LogRotator(int, int, int, int, boolean, boolean)}
     */
    @Deprecated
    public LogRotator(int daysToKeep, int numToKeep) {
        this(daysToKeep, numToKeep, -1, -1, false, false);
    }

    /**
     * @deprecated since TBD
     *      Use {@link #LogRotator(int, int, int, int, boolean, boolean)}
     */
    @Deprecated
    public LogRotator(int daysToKeep, int numToKeep, int artifactDaysToKeep, int artifactNumToKeep) {
        this(daysToKeep, numToKeep, artifactDaysToKeep, artifactNumToKeep, false, false);
    }

    public LogRotator(int daysToKeep, int numToKeep, int artifactDaysToKeep, int artifactNumToKeep, boolean upstreamKeep, boolean upstreamKeepArtifacts) {
        this.daysToKeep = daysToKeep;
        this.numToKeep = numToKeep;
        this.artifactDaysToKeep = artifactDaysToKeep;
        this.artifactNumToKeep = artifactNumToKeep;
        this.upstreamKeep = upstreamKeep;
        this.upstreamKeepArtifacts = upstreamKeepArtifacts;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void perform(Job<?,?> job) throws IOException, InterruptedException {
        synchronized(LogRotator.class) {
            if ( currentCleanupJobs.contains(job) ) {
                LOGGER.log(FINER, "Log rotation for {0} is already running", job);
                return;
            }
            currentCleanupJobs.add(job);
        }
        
        try {
            LOGGER.log(FINE, "Running the log rotation for {0} with numToKeep={1} daysToKeep={2} artifactNumToKeep={3} artifactDaysToKeep={4} upstreamKeep={5} upstreamKeepArtifacts={6}",
                    new Object[] {job, numToKeep, daysToKeep, artifactNumToKeep, artifactDaysToKeep, upstreamKeep, upstreamKeepArtifacts});

            // always keep the last successful and the last stable builds
            Run lsb = job.getLastSuccessfulBuild();
            Run lstb = job.getLastStableBuild();
            int artifactNumToKeep = this.artifactNumToKeep != null ? this.artifactNumToKeep : -1; // handle possible null
            int artifactDaysToKeep = this.artifactDaysToKeep != null ? this.artifactDaysToKeep : -1; // handle possible null

            if ( numToKeep!=-1 || artifactNumToKeep!=-1 ) {
                // Note that RunList.size is deprecated, and indeed here we are loading all the builds of the job.
                // However we would need to load the first numToKeep anyway, just to skip over them;
                // and we would need to load the rest anyway, to delete them.
                // (Using RunMap.headMap would not suffice, since we do not know if some recent builds have been deleted for other reasons,
                // so simply subtracting numToKeep from the currently last build number might cause us to delete too many.)
                List<? extends Run<?,?>> builds = job.getBuilds();
                int i = Math.min(numToKeep, artifactNumToKeep);
                if ( i<0 ) i = Math.max(numToKeep, artifactNumToKeep);
                for (Run r : copy(builds.subList(i, builds.size()))) {
                    boolean deleted = false;
                    if ( numToKeep!=-1 && numToKeep <= i ) {
                        if ( !shouldKeepRun(r, lsb, lstb) ) {
                            LOGGER.log(FINER, "{0} is to be removed", r);
                            r.delete();
                            deleted = true;
                        }
                    }

                    if ( !deleted && artifactNumToKeep!=-1 && artifactNumToKeep <= i ) {
                        if ( !shouldKeepRunArtifacts(r, lsb, lstb) ) {
                            LOGGER.log(FINER, "{0} is to be purged of artifacts", r);
                            r.deleteArtifacts();
                        }
                    }

                    i++;
                }
            }

            if ( daysToKeep!=-1 || artifactDaysToKeep!=-1 ) {
                Calendar buildCal = new GregorianCalendar();
                buildCal.add(Calendar.DAY_OF_YEAR,-daysToKeep);
                Calendar artifactCal = new GregorianCalendar();
                artifactCal.add(Calendar.DAY_OF_YEAR,-artifactDaysToKeep);

                Run r = job.getFirstBuild();
                while (r != null) {
                    boolean deleted = false;
                    if( daysToKeep!=-1 ) {
                        if ( !tooNew(r, buildCal) && !shouldKeepRun(r, lsb, lstb) ) {
                            LOGGER.log(FINER, "{0} is to be removed", r);
                            r.delete();
                        }
                    }
                    if ( !deleted && artifactDaysToKeep!=-1 ) {
                        if ( !tooNew(r, artifactCal) && !shouldKeepRunArtifacts(r, lsb, lstb) ) {
                            LOGGER.log(FINER, "{0} is to be purged of artifacts", r);
                            r.deleteArtifacts();
                        }
                    }

                    if ( (daysToKeep==-1 || tooNew(r, buildCal)) && (artifactDaysToKeep==-1 || tooNew(r, artifactCal)) ) {
                        break;
                    }

                    r = r.getNextBuild();
                }
            }
        }
        finally {
            synchronized(LogRotator.class) {
                currentCleanupJobs.remove(job);
            }
        }
    }

    private boolean shouldKeepRun(Run r, Run lsb, Run lstb) {
        if ( shouldKeepCompleteRun(r, lsb, lstb) ) return true;
        if ( upstreamKeep && upstreamBuildsExist(r) ) {
            LOGGER.log(FINEST, "{0} is not to be removed because an upstream cause still exists", r);
            return true;
        }
        return false;
    }

    private boolean shouldKeepRunArtifacts(Run r, Run lsb, Run lstb) {
        if ( shouldKeepCompleteRun(r, lsb, lstb) ) return true;
        if ( upstreamKeep && upstreamKeepArtifacts && upstreamBuildsExist(r) ) {
            LOGGER.log(FINEST, "{0} is not to be removed or purged of artifacts because an upstream cause still exists", r);
            return true;
        }
        return false;
    }

    private boolean shouldKeepCompleteRun(Run r, Run lsb, Run lstb) {
        if (r.isKeepLog()) {
            LOGGER.log(FINEST, "{0} is not to be removed or purged of artifacts because it’s marked as a keeper", r);
            return true;
        }
        if (r == lsb) {
            LOGGER.log(FINEST, "{0} is not to be removed or purged of artifacts because it’s the last successful build", r);
            return true;
        }
        if (r == lstb) {
            LOGGER.log(FINEST, "{0} is not to be removed or purged of artifacts because it’s the last stable build", r);
            return true;
        }
        if (r.isBuilding()) {
            LOGGER.log(FINEST, "{0} is not to be removed or purged of artifacts because it’s still building", r);
            return true;
        }
        return false;
    }

    private boolean tooNew(Run r, Calendar cal) {
        if (!r.getTimestamp().before(cal)) {
            LOGGER.log(FINEST, "{0} is not to be removed or purged of artifacts because it’s still new", r);
            return true;
        } else {
            return false;
        }
    }

    private boolean upstreamBuildsExist(Run<?,?> r) {
        for ( Cause c : r.getCauses() ) {
            if ( c instanceof Cause.UpstreamCause ) {
                if ( ((Cause.UpstreamCause)c).getUpstreamRun() != null ) return true;
            }
        }
        return false;
    }

    /**
     * Creates a copy since we'll be deleting some entries from them.
     */
    private <R> Collection<R> copy(Iterable<R> src) {
        return Lists.newArrayList(src);
    }

    public int getDaysToKeep() {
        return daysToKeep;
    }

    public int getNumToKeep() {
        return numToKeep;
    }

    public int getArtifactDaysToKeep() {
        return unbox(artifactDaysToKeep);
    }

    public int getArtifactNumToKeep() {
        return unbox(artifactNumToKeep);
    }

    public String getDaysToKeepStr() {
        return toString(daysToKeep);
    }

    public String getNumToKeepStr() {
        return toString(numToKeep);
    }

    public String getArtifactDaysToKeepStr() {
        return toString(artifactDaysToKeep);
    }

    public String getArtifactNumToKeepStr() {
        return toString(artifactNumToKeep);
    }

    public boolean isUpstreamKeep() {
        return upstreamKeep;
    }

    public boolean isUpstreamKeepArtifacts() {
        return upstreamKeepArtifacts;
    }

    private int unbox(Integer i) {
        return i==null ? -1: i;
    }

    private String toString(Integer i) {
        if (i==null || i==-1)   return "";
        return String.valueOf(i);
    }

    @Extension
    public static final class LRDescriptor extends BuildDiscarderDescriptor {
        @Override
        public String getDisplayName() {
            return "Log Rotation Extended";
        }
    }

    private static final Logger LOGGER = Logger.getLogger(LogRotator.class.getName());
}
