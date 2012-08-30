/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.plugins.buildcomparison.gradle;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.*;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.filestore.FileStore;
import org.gradle.api.internal.filestore.PathNormalisingKeyFileStore;
import org.gradle.api.plugins.buildcomparison.compare.internal.*;
import org.gradle.api.plugins.buildcomparison.gradle.internal.DefaultGradleBuildInvocationSpec;
import org.gradle.api.plugins.buildcomparison.gradle.internal.GradleBuildOutcomeSetTransformer;
import org.gradle.api.plugins.buildcomparison.outcome.internal.BuildOutcome;
import org.gradle.api.plugins.buildcomparison.outcome.internal.BuildOutcomeAssociator;
import org.gradle.api.plugins.buildcomparison.outcome.internal.ByTypeAndNameBuildOutcomeAssociator;
import org.gradle.api.plugins.buildcomparison.outcome.internal.CompositeBuildOutcomeAssociator;
import org.gradle.api.plugins.buildcomparison.outcome.internal.archive.GeneratedArchiveBuildOutcome;
import org.gradle.api.plugins.buildcomparison.outcome.internal.archive.GeneratedArchiveBuildOutcomeComparator;
import org.gradle.api.plugins.buildcomparison.outcome.internal.archive.entry.GeneratedArchiveBuildOutcomeComparisonResultHtmlRenderer;
import org.gradle.api.plugins.buildcomparison.outcome.internal.unknown.UnknownBuildOutcome;
import org.gradle.api.plugins.buildcomparison.outcome.internal.unknown.UnknownBuildOutcomeComparator;
import org.gradle.api.plugins.buildcomparison.outcome.internal.unknown.UnknownBuildOutcomeComparisonResultHtmlRenderer;
import org.gradle.api.plugins.buildcomparison.render.internal.BuildComparisonResultRenderer;
import org.gradle.api.plugins.buildcomparison.render.internal.DefaultBuildOutcomeComparisonResultRendererFactory;
import org.gradle.api.plugins.buildcomparison.render.internal.html.*;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.logging.ProgressLogger;
import org.gradle.logging.ProgressLoggerFactory;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.internal.outcomes.ProjectOutcomes;
import org.gradle.util.GFileUtils;
import org.gradle.util.GradleVersion;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Executes two Gradle builds (that can be the same build) with specified versions and compares the outputs.
 */
public class CompareGradleBuilds extends DefaultTask {

    private static final List<String> DEFAULT_TASKS = Arrays.asList("clean", "assemble");
    private static final String TMP_FILESTORAGE_PREFIX = "tmp-filestorage";

    private static final GradleVersion PROJECT_OUTCOMES_MINIMUM_VERSION = GradleVersion.version("1.2");

    private final DefaultGradleBuildInvocationSpec sourceBuild;
    private final DefaultGradleBuildInvocationSpec targetBuild;

    private Object reportDir;

    private final FileStore<String> fileStore;

    private final FileResolver fileResolver;
    private final ProgressLoggerFactory progressLoggerFactory;

    public CompareGradleBuilds(FileResolver fileResolver, ProgressLoggerFactory progressLoggerFactory, Instantiator instantiator) {
        this.fileResolver = fileResolver;
        this.progressLoggerFactory = progressLoggerFactory;

        sourceBuild = instantiator.newInstance(DefaultGradleBuildInvocationSpec.class, fileResolver, getProject().getRootDir());
        sourceBuild.setTasks(DEFAULT_TASKS);
        targetBuild = instantiator.newInstance(DefaultGradleBuildInvocationSpec.class, fileResolver, getProject().getRootDir());
        targetBuild.setTasks(DEFAULT_TASKS);

        File fileStoreTmpBase = fileResolver.resolve(String.format(TMP_FILESTORAGE_PREFIX + "-%s-%s", getName(), System.currentTimeMillis()));
        fileStore = new PathNormalisingKeyFileStore(fileStoreTmpBase);

        // Never up to date
        getOutputs().upToDateWhen(new Spec<Task>() {
            public boolean isSatisfiedBy(Task element) {
                return false;
            }
        });
    }

    public GradleBuildInvocationSpec getSourceBuild() {
        return sourceBuild;
    }

    public void sourceBuild(Action<GradleBuildInvocationSpec> config) {
        config.execute(getSourceBuild());
    }

    public GradleBuildInvocationSpec getTargetBuild() {
        return targetBuild;
    }

    public void targetBuild(Action<GradleBuildInvocationSpec> config) {
        config.execute(getTargetBuild());
    }

    @OutputDirectory
    public File getReportDir() {
        return reportDir == null ? null : fileResolver.resolve(reportDir);
    }

    public void setReportDir(Object reportDir) {
        if (reportDir == null) {
            throw new IllegalArgumentException("reportDir cannot be null");
        }
        this.reportDir = reportDir;
    }

    public File getReportFile() {
        return new File(getReportDir(), "index.html");
    }

    public File getFileStoreDir() {
        return new File(getReportDir(), "files");
    }

    @TaskAction
    void compare() {
        if (sourceBuild.equals(targetBuild)) {
            getLogger().warn("The source build and target build are identical. Set '{}.targetBuild.gradleVersion' if you want to compare with a different Gradle version.", getName());
        }

        boolean sourceBuildHasOutcomesModel = canObtainProjectOutcomesModel(sourceBuild);
        boolean targetBuildHasOutcomesModel = canObtainProjectOutcomesModel(targetBuild);

        if (!sourceBuildHasOutcomesModel && !targetBuildHasOutcomesModel) {
            throw new GradleException(String.format(
                    "Cannot run comparison because both the source and target build are to be executed with a Gradle version older than %s.",
                    PROJECT_OUTCOMES_MINIMUM_VERSION
            ));
        }

        ProgressLogger progressLogger = progressLoggerFactory.newOperation(getClass());

        progressLogger.setDescription("Gradle Build Comparison");
        progressLogger.setShortDescription(getName());

        // Build the outcome model and outcomes
        progressLogger.started("executing source build");
        GradleBuildOutcomeSetTransformer fromOutcomeTransformer = createOutcomeSetTransformer("source");
        ProjectOutcomes fromOutput = buildProjectOutcomes(getSourceBuild());
        progressLogger.progress("inspecting source build outcomes");
        Set<BuildOutcome> fromOutcomes = fromOutcomeTransformer.transform(fromOutput);

        progressLogger.progress("executing target build");
        GradleBuildOutcomeSetTransformer toOutcomeTransformer = createOutcomeSetTransformer("target");
        ProjectOutcomes toOutput = buildProjectOutcomes(getTargetBuild());
        progressLogger.progress("inspecting target build outcomes");
        Set<BuildOutcome> toOutcomes = toOutcomeTransformer.transform(toOutput);

        progressLogger.progress("preparing for comparison");

        // Infrastructure that we have to register handlers with
        DefaultBuildOutcomeComparatorFactory comparatorFactory = new DefaultBuildOutcomeComparatorFactory();
        BuildOutcomeAssociator[] associators = new BuildOutcomeAssociator[2];
        DefaultBuildOutcomeComparisonResultRendererFactory<HtmlRenderContext> renderers = new DefaultBuildOutcomeComparisonResultRendererFactory<HtmlRenderContext>(HtmlRenderContext.class);

        // Register archives
        associators[0] = new ByTypeAndNameBuildOutcomeAssociator<BuildOutcome>(GeneratedArchiveBuildOutcome.class);
        comparatorFactory.registerComparator(new GeneratedArchiveBuildOutcomeComparator());
        renderers.registerRenderer(new GeneratedArchiveBuildOutcomeComparisonResultHtmlRenderer("Source Build", "Target Build"));

        // Register unknown handling
        associators[1] = new ByTypeAndNameBuildOutcomeAssociator<BuildOutcome>(UnknownBuildOutcome.class);
        comparatorFactory.registerComparator(new UnknownBuildOutcomeComparator());
        renderers.registerRenderer(new UnknownBuildOutcomeComparisonResultHtmlRenderer("Source Build", "Target Build"));

        // Associate from each side (create spec)
        BuildOutcomeAssociator compositeAssociator = new CompositeBuildOutcomeAssociator(associators);
        BuildComparisonSpecFactory specFactory = new BuildComparisonSpecFactory(compositeAssociator);
        BuildComparisonSpec comparisonSpec = specFactory.createSpec(fromOutcomes, toOutcomes);

        progressLogger.progress("comparing build outcomes");

        // Compare
        BuildComparator buildComparator = new DefaultBuildComparator(comparatorFactory);
        BuildComparisonResult result = buildComparator.compareBuilds(comparisonSpec);

        writeReport(result, renderers);

        progressLogger.completed();
    }

    private GradleBuildOutcomeSetTransformer createOutcomeSetTransformer(String filesPath) {
        return new GradleBuildOutcomeSetTransformer(fileStore, filesPath);
    }

    private ProjectOutcomes buildProjectOutcomes(GradleBuildInvocationSpec spec) {
        GradleVersion gradleVersion = GradleVersion.version(spec.getGradleVersion());

        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(spec.getProjectDir());
        connector.useGradleUserHomeDir(getProject().getGradle().getStartParameter().getGradleUserHomeDir());
        if (gradleVersion.equals(GradleVersion.current())) {
            connector.useInstallation(getProject().getGradle().getGradleHomeDir());
        } else {
            connector.useGradleVersion(gradleVersion.getVersion());
        }

        ProjectConnection connection = connector.connect();
        try {
            List<String> tasksList = spec.getTasks();
            String[] tasks = tasksList.toArray(new String[tasksList.size()]);
            List<String> argumentsList = getImpliedArguments(spec);
            String[] arguments = argumentsList.toArray(new String[argumentsList.size()]);

            // Run the build and get the build outcomes model
            ModelBuilder<ProjectOutcomes> modelBuilder = connection.model(ProjectOutcomes.class);
            return modelBuilder.
                    withArguments(arguments).
                    forTasks(tasks).
                    get();
        } finally {
            connection.close();
        }
    }

    private List<String> getImpliedArguments(GradleBuildInvocationSpec spec) {
        List<String> rawArgs = spec.getArguments();

        // Note: we don't know for certain that this is how to invoke this functionality for this Gradle version.
        //       unsure of any other alternative.
        if (rawArgs.contains("-u") || rawArgs.contains("--no-search-upward")) {
            return rawArgs;
        } else {
            List<String> ammendedArgs = new ArrayList<String>(rawArgs.size() + 1);
            ammendedArgs.add("--no-search-upward");
            ammendedArgs.addAll(rawArgs);
            return ammendedArgs;
        }
    }

    private void writeReport(BuildComparisonResult result, DefaultBuildOutcomeComparisonResultRendererFactory<HtmlRenderContext> renderers) {
        File reportDir = getReportDir();
        if (reportDir.exists() && reportDir.list().length > 0) {
            GFileUtils.cleanDirectory(reportDir);
        }

        fileStore.moveFilestore(getFileStoreDir());

        OutputStream outputStream;
        Writer writer;

        try {
            outputStream = FileUtils.openOutputStream(getReportFile());
            writer = new OutputStreamWriter(outputStream, Charset.defaultCharset());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try {
            createResultRenderer(renderers).render(result, writer);
        } finally {
            IOUtils.closeQuietly(writer);
            IOUtils.closeQuietly(outputStream);
        }
    }

    private BuildComparisonResultRenderer<Writer> createResultRenderer(DefaultBuildOutcomeComparisonResultRendererFactory<HtmlRenderContext> renderers) {
        PartRenderer headRenderer = new HeadRenderer("Gradle Build Comparison", Charset.defaultCharset().name());

        PartRenderer headingRenderer = new GradleComparisonHeadingRenderer(getSourceBuild(), getTargetBuild());

        return new HtmlBuildComparisonResultRenderer(renderers, headRenderer, headingRenderer, null);
    }

    private boolean canObtainProjectOutcomesModel(GradleBuildInvocationSpec spec) {
        GradleVersion versionObject = GradleVersion.version(spec.getGradleVersion());
        boolean isMinimumVersionOrHigher = versionObject.compareTo(PROJECT_OUTCOMES_MINIMUM_VERSION) >= 0;
        //noinspection SimplifiableIfStatement
        if (isMinimumVersionOrHigher) {
            return true;
        } else {
            // Special handling for snapshots/RCs of the minimum version
            return versionObject.getVersion().equals(PROJECT_OUTCOMES_MINIMUM_VERSION.getVersion());
        }
    }
}
