/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.tasks.Publisher;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.TestBuilder;

/**
 * Verifies that the last successful and stable builds of a job will be kept if requested.
 */
public class LogRotatorTest extends HudsonTestCase {

    public void testSuccessVsFailure() throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        project.setBuildDiscarder(new LogRotator(-1, 2, -1, -1));
        assertEquals(Result.SUCCESS, build(project)); // #1
        project.getBuildersList().replaceBy(Collections.singleton(new FailureBuilder()));
        assertEquals(Result.FAILURE, build(project)); // #2
        assertEquals(Result.FAILURE, build(project)); // #3
        assertEquals(1, numberOf(project.getLastSuccessfulBuild()));
        project.getBuildersList().replaceBy(Collections.<Builder>emptySet());
        assertEquals(Result.SUCCESS, build(project)); // #4
        assertEquals(4, numberOf(project.getLastSuccessfulBuild()));
        assertEquals(null, project.getBuildByNumber(1));
        assertEquals(null, project.getBuildByNumber(2));
        assertEquals(3, numberOf(project.getLastFailedBuild()));
    }

    @Bug(2417)
    public void testStableVsUnstable() throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        project.setBuildDiscarder(new LogRotator(-1, 2, -1, -1));
        assertEquals(Result.SUCCESS, build(project)); // #1
        project.getPublishersList().replaceBy(Collections.singleton(new TestsFail()));
        assertEquals(Result.UNSTABLE, build(project)); // #2
        assertEquals(Result.UNSTABLE, build(project)); // #3
        assertEquals(1, numberOf(project.getLastStableBuild()));
        project.getPublishersList().replaceBy(Collections.<Publisher>emptySet());
        assertEquals(Result.SUCCESS, build(project)); // #4
        assertEquals(null, project.getBuildByNumber(1));
        assertEquals(null, project.getBuildByNumber(2));
    }

    @Bug(834)
    public void testArtifactDelete() throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        project.setBuildDiscarder(new LogRotator(-1, 6, -1, 2));
        project.getPublishersList().replaceBy(Collections.singleton(new ArtifactArchiver("f", "", true, false)));
        assertEquals("(no artifacts)", Result.FAILURE, build(project)); // #1
        assertFalse(project.getBuildByNumber(1).getHasArtifacts());
        project.getBuildersList().replaceBy(Collections.singleton(new CreateArtifact()));
        assertEquals(Result.SUCCESS, build(project)); // #2
        assertTrue(project.getBuildByNumber(2).getHasArtifacts());
        project.getBuildersList().replaceBy(Arrays.asList(new CreateArtifact(), new FailureBuilder()));
        assertEquals(Result.FAILURE, build(project)); // #3
        assertTrue(project.getBuildByNumber(2).getHasArtifacts());
        assertTrue(project.getBuildByNumber(3).getHasArtifacts());
        assertEquals(Result.FAILURE, build(project)); // #4
        assertTrue(project.getBuildByNumber(2).getHasArtifacts());
        assertTrue(project.getBuildByNumber(3).getHasArtifacts());
        assertTrue(project.getBuildByNumber(4).getHasArtifacts());
        assertEquals(Result.FAILURE, build(project)); // #5
        assertTrue(project.getBuildByNumber(2).getHasArtifacts());
        assertFalse("no better than #4", project.getBuildByNumber(3).getHasArtifacts());
        assertTrue(project.getBuildByNumber(4).getHasArtifacts());
        assertTrue(project.getBuildByNumber(5).getHasArtifacts());
        project.getBuildersList().replaceBy(Collections.singleton(new CreateArtifact()));
        assertEquals(Result.SUCCESS, build(project)); // #6
        assertFalse("#2 is still lastSuccessful until #6 is complete", project.getBuildByNumber(2).getHasArtifacts());
        assertFalse(project.getBuildByNumber(3).getHasArtifacts());
        assertFalse(project.getBuildByNumber(4).getHasArtifacts());
        assertTrue(project.getBuildByNumber(5).getHasArtifacts());
        assertTrue(project.getBuildByNumber(6).getHasArtifacts());
        assertEquals(Result.SUCCESS, build(project)); // #7
        assertEquals(null, project.getBuildByNumber(1));
        assertNotNull(project.getBuildByNumber(2));
        assertFalse("lastSuccessful was #6 for ArtifactArchiver", project.getBuildByNumber(2).getHasArtifacts());
        assertFalse(project.getBuildByNumber(3).getHasArtifacts());
        assertFalse(project.getBuildByNumber(4).getHasArtifacts());
        assertFalse(project.getBuildByNumber(5).getHasArtifacts());
        assertTrue(project.getBuildByNumber(6).getHasArtifacts());
        assertTrue(project.getBuildByNumber(7).getHasArtifacts());
        assertEquals(Result.SUCCESS, build(project)); // #8
        assertEquals(null, project.getBuildByNumber(2));
        assertNotNull(project.getBuildByNumber(3));
        assertFalse(project.getBuildByNumber(3).getHasArtifacts());
        assertFalse(project.getBuildByNumber(4).getHasArtifacts());
        assertFalse(project.getBuildByNumber(5).getHasArtifacts());
        assertFalse(project.getBuildByNumber(6).getHasArtifacts());
        assertTrue(project.getBuildByNumber(7).getHasArtifacts());
        assertTrue(project.getBuildByNumber(8).getHasArtifacts());
    }

    public void testUpstreamKeep() throws Exception {
        FreeStyleProject upstream = createFreeStyleProject();
        FreeStyleProject project = createFreeStyleProject();
        project.setBuildDiscarder(new LogRotator(-1, 6, -1, 2, true, false));
        project.getBuildersList().replaceBy(Collections.singleton(new CreateArtifact()));
        project.getPublishersList().replaceBy(Collections.singleton(new ArtifactArchiver("f", "", true, false)));
        Run[] u = new Run[4];
        Cause[] c = new Cause[4];
        for ( int i=0; i<4; i++ ) {
            u[i] = upstream.scheduleBuild2(0).get();
            c[i] = new Cause.UpstreamCause(u[i]);
        };

        for ( int i=1; i<=8; i++ ) {
            assertEquals(Result.SUCCESS, build(project, c[(i-1)%4]));
            assertTrue(project.getBuildByNumber(i).getHasArtifacts());
        }
        for ( int i=1; i<=6; i++ ) {
            assertFalse(project.getBuildByNumber(i).getHasArtifacts());
        }
        for ( int i=7; i<=8; i++ ) {
            assertTrue(project.getBuildByNumber(i).getHasArtifacts());
        }
        u[1].delete();
        assertEquals(Result.SUCCESS, build(project));
        assertEquals(Result.SUCCESS, build(project));
        assertEquals(Result.SUCCESS, build(project));
        assertEquals(Result.SUCCESS, build(project));

        assertFalse(project.getBuildByNumber(1).getHasArtifacts());
        assertNull(project.getBuildByNumber(2));
        assertFalse(project.getBuildByNumber(3).getHasArtifacts());
        assertFalse(project.getBuildByNumber(4).getHasArtifacts());
        assertFalse(project.getBuildByNumber(5).getHasArtifacts());
        assertNull(project.getBuildByNumber(6));
        assertFalse(project.getBuildByNumber(7).getHasArtifacts());
        assertFalse(project.getBuildByNumber(8).getHasArtifacts());
        assertFalse(project.getBuildByNumber(9).getHasArtifacts());
        assertFalse(project.getBuildByNumber(10).getHasArtifacts());
        assertTrue(project.getBuildByNumber(11).getHasArtifacts());
        assertTrue(project.getBuildByNumber(12).getHasArtifacts());
    }

    public void testArtifactUpstreamKeep() throws Exception {
        FreeStyleProject upstream = createFreeStyleProject();
        FreeStyleProject project = createFreeStyleProject();
        project.setBuildDiscarder(new LogRotator(-1, 6, -1, 2, true, true));
        project.getBuildersList().replaceBy(Collections.singleton(new CreateArtifact()));
        project.getPublishersList().replaceBy(Collections.singleton(new ArtifactArchiver("f", "", true, false)));
        Run[] u = new Run[4];
        Cause[] c = new Cause[4];
        for ( int i=0; i<4; i++ ) {
            u[i] = upstream.scheduleBuild2(0).get();
            c[i] = new Cause.UpstreamCause(u[i]);
        };

        for ( int i=1; i<=8; i++ ) {
            assertEquals(Result.SUCCESS, build(project, c[(i-1)%4]));
            assertTrue(project.getBuildByNumber(i).getHasArtifacts());
        }
        for ( int i=1; i<=8; i++ ) {
            assertTrue(project.getBuildByNumber(i).getHasArtifacts());
        }
        u[0].delete();
        assertEquals(Result.SUCCESS, build(project));
        assertEquals(Result.SUCCESS, build(project));
        assertEquals(Result.SUCCESS, build(project));
        assertEquals(Result.SUCCESS, build(project));

        assertNull(project.getBuildByNumber(1));
        assertTrue(project.getBuildByNumber(2).getHasArtifacts());
        assertTrue(project.getBuildByNumber(3).getHasArtifacts());
        assertTrue(project.getBuildByNumber(4).getHasArtifacts());
        assertNull(project.getBuildByNumber(5));
        assertTrue(project.getBuildByNumber(6).getHasArtifacts());
        assertTrue(project.getBuildByNumber(7).getHasArtifacts());
        assertTrue(project.getBuildByNumber(8).getHasArtifacts());
        assertFalse(project.getBuildByNumber(9).getHasArtifacts());
        assertFalse(project.getBuildByNumber(10).getHasArtifacts());
        assertTrue(project.getBuildByNumber(11).getHasArtifacts());
        assertTrue(project.getBuildByNumber(12).getHasArtifacts());
    }


    static Result build(FreeStyleProject project) throws Exception {
        return project.scheduleBuild2(0).get().getResult();
    }

    static Result build(FreeStyleProject project, Cause c) throws Exception {
        return project.scheduleBuild2(0, c).get().getResult();
    }

    private static int numberOf(Run<?,?> run) {
        return run != null ? run.getNumber() : -1;
    }

    static class TestsFail extends Publisher {
        public @Override boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) {
            build.setResult(Result.UNSTABLE);
            return true;
        }

        public BuildStepMonitor getRequiredMonitorService() {
            return BuildStepMonitor.NONE;
        }

        public Descriptor<Publisher> getDescriptor() {
            return new Descriptor<Publisher>(TestsFail.class) {
                public String getDisplayName() {
                    return "TestsFail";
                }
            };
        }
    }

	static class CreateArtifact extends TestBuilder {
        public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
            build.getWorkspace().child("f").write("content", "UTF-8");
            return true;
        }
    }

}
