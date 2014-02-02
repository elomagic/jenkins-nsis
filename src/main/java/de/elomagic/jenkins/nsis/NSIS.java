/*
 * The MIT License
 *
 * Copyright 2014 Carsten Rambow, elomagic, Karlsruhe Germany.
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
package de.elomagic.jenkins.nsis;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tools.DownloadFromUrlInstaller;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.NullStream;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;

/**
 * Builder class of the Jenkins NSIS plugin.
 *
 * @author Carsten Rambow
 */
public class NSIS extends Builder {

    private final String nsisName;
    private final String options;
    private final String scriptName;

    @DataBoundConstructor
    public NSIS(final String name, final String options, final String scriptName) {
        this.nsisName = name;
        this.options = options;
        this.scriptName = scriptName;
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws InterruptedException, IOException {
        EnvVars env = build.getEnvironment(listener);

        ArgumentListBuilder args = new ArgumentListBuilder();

        NSISInstallation mi = getNsis();
        if(mi == null) {
            //String execName = build.getWorkspace().act(new DecideDefaultMavenCommand(normalizedTarget));
            //args.add(execName);
        } else {
            mi = mi.forNode(Computer.currentComputer().getNode(), listener);
            mi = mi.forEnvironment(env);
            String exec = mi.getExecutable(launcher);
            if(exec == null) {
                listener.fatalError(MessageFormat.format("Couldn’t find any executable in {0}", mi.getHome()));
                return false;
            }
            args.add(exec);
        }

        if(options != null) {
            String optionArgs = options.replaceAll("[\t\r\n]+", " ");
            optionArgs = Util.replaceMacro(optionArgs, env);
            optionArgs = Util.replaceMacro(optionArgs, build.getBuildVariables());
            args.addTokenized(optionArgs);
        }

        if(scriptName != null) {
            String name = scriptName.replaceAll("[\t\r\n]+", " ");
            name = Util.replaceMacro(name, env);
            name = Util.replaceMacro(name, build.getBuildVariables());
            args.add(name);
        }

        buildEnvVars(env, mi);

        if(!launcher.isUnix()) {
            args.prepend("cmd.exe", "/C");
            args.add("&&", "exit", "%%ERRORLEVEL%%");
        }

        try {
            int exitcode = launcher
                    .launch()
                    .cmds(args)
                    .envs(env)
                    .stdout(listener)
                    .pwd(build.getModuleRoot())
                    .join();

            return exitcode == 0;
        } catch(IOException ex) {
            Util.displayIOException(ex, listener);
            ex.printStackTrace(listener.fatalError("command execution failed"));
            return false;
        }
    }

    public String getNsisName() {
        return nsisName;
    }

    public String getOptions() {
        return options;
    }

    public String getScriptName() {
        return scriptName;
    }

    /**
     * Build up the environment variables toward the NSIS launch.
     *
     * @param env
     * @param mi
     * @throws java.io.IOException
     * @throws java.lang.InterruptedException
     */
    protected void buildEnvVars(final EnvVars env, final NSIS.NSISInstallation mi) throws IOException, InterruptedException {
        if(mi != null) {
            mi.buildEnvVars(env);
        }
        //env.put("NSISDIR", nsisFolder);
        //env.put("NSISCONFDIR", nsisConfigurationFolder);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    public NSISInstallation getNsis() {
        for(NSISInstallation i : getDescriptor().getInstallations()) {
            if(nsisName != null && nsisName.equals(i.getName())) {
                return i;
            }
        }

        return null;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @CopyOnWrite
        private volatile NSISInstallation[] installations = new NSISInstallation[0];

        public DescriptorImpl() {
            load();
        }

        public NSISInstallation[] getInstallations() {
            return installations;
        }

        public void setInstallations(final NSISInstallation... installations) {
            List tmpList = new ArrayList();
            // remote empty Maven installation :
            if(installations != null) {
                Collections.addAll(tmpList, installations);
                for(NSISInstallation installation : installations) {
                    if(Util.fixEmptyAndTrim(installation.getName()) == null) {
                        tmpList.remove(installation);
                    }
                }
            }
            this.installations = (NSISInstallation[])tmpList.toArray(new NSISInstallation[tmpList.size()]);

            save();
        }

        public FormValidation doCheckScriptName(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        @Override
        public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "NSIS";
        }

    }

    public static final class NSISInstallation extends ToolInstallation {

        @DataBoundConstructor
        public NSISInstallation(final String name, final String home, final List<? extends ToolProperty<?>> properties) {
            super(Util.fixEmptyAndTrim(name), Util.fixEmptyAndTrim(home), properties);
        }

        @Extension
        public static class DescriptorImpl extends ToolDescriptor<NSISInstallation> {

            @Override
            public List<? extends ToolInstaller> getDefaultInstallers() {
                return Collections.singletonList(new NSISInstaller(null));
            }

            @Override
            public String getDisplayName() {
                return "NSIS";
            }

            // overriding them for backward compatibility.
            // newer code need not do this
            @Override
            public NSISInstallation[] getInstallations() {
                return Jenkins.getInstance().getDescriptorByType(NSIS.DescriptorImpl.class).getInstallations();
            }

            // overriding them for backward compatibility.
            // newer code need not do this
            @Override
            public void setInstallations(final NSISInstallation... installations) {
                Jenkins.getInstance().getDescriptorByType(NSIS.DescriptorImpl.class).setInstallations(installations);
            }

            public FormValidation doCheckName(@QueryParameter String value) {
                return FormValidation.validateRequired(value);
            }
        }

        /**
         * Gets the executable path of this NSIS on the given target system.
         *
         * @param launcher
         * @return
         * @throws java.io.IOException
         * @throws java.lang.InterruptedException
         */
        public String getExecutable(final Launcher launcher) throws IOException, InterruptedException {
            return launcher.getChannel().call(new Callable<String, IOException>() {
                private static final long serialVersionUID = 2373163112639943768L;

                @Override
                public String call() throws IOException {
                    String nsisHome = Util.replaceMacro(getHome(), EnvVars.masterEnvVars);
                    File exe = new File(nsisHome, "makensis.exe");

                    return exe.exists() ? exe.getPath() : null;
                }
            });
        }

        /**
         * Returns true if the executable exists.
         *
         * @return
         */
        public boolean getExists() {
            try {
                return getExecutable(new Launcher.LocalLauncher(new StreamTaskListener(new NullStream()))) != null;
            } catch(IOException ex) {
                return false;
            } catch(InterruptedException e) {
                return false;
            }
        }

        public NSISInstallation forEnvironment(final EnvVars environment) {
            return new NSISInstallation(getName(), environment.expand(getHome()), getProperties().toList());
        }

        public NSISInstallation forNode(final Node node, final TaskListener log) throws IOException, InterruptedException {
            return new NSISInstallation(getName(), translateFor(node, log), getProperties().toList());
        }
    }

    public static class NSISInstaller extends DownloadFromUrlInstaller {

        @DataBoundConstructor
        public NSISInstaller(final String id) {
            super(id);
        }

        @Extension
        public static final class DescriptorImpl extends DownloadFromUrlInstaller.DescriptorImpl<NSISInstaller> {
            @Override
            public String getDisplayName() {
                return "Install from NSIS";
            }

            @Override
            public boolean isApplicable(final Class<? extends ToolInstallation> toolType) {
                return toolType == NSISInstallation.class;
            }

            @Override
            public List<? extends Installable> getInstallables() throws IOException {
                Installable i = new Installable();
                i.id = "2.46";
                i.name = "NSIS 2.46";
                i.url = "http://switch.dl.sourceforge.net/project/nsis/NSIS%202/2.46/nsis-2.46.zip";

                return Collections.singletonList(i);
            }
        }
    }

}
