package pokechu22.test.begradle.baseedit;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.file.CopySpec;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;
import org.gradle.plugins.ide.api.XmlFileContentMerger;
import org.gradle.plugins.ide.eclipse.model.Classpath;
import org.gradle.plugins.ide.eclipse.model.ClasspathEntry;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.SourceFolder;

import com.google.common.collect.ImmutableMap;

import net.minecraftforge.gradle.common.task.ExtractMCPData;
import net.minecraftforge.gradle.common.util.MavenArtifactDownloader;
import net.minecraftforge.gradle.mcp.task.GenerateSRG;
import net.minecraftforge.gradle.userdev.UserDevExtension;
import net.minecraftforge.gradle.userdev.UserDevPlugin;
import net.minecraftforge.gradle.userdev.tasks.RenameJarInPlace;

public class BaseEditPlugin extends UserDevPlugin {
	// Null until apply
	private Project project;
	// Null until afterEvaluate
	private String mcSourcesJarDesc, mcBinJarDesc;
	// Null until getMcSourcesJar/getMcBinJar is called
	private File mcSourcesJar, mcBinJar;

	@Override
	public void apply(@Nonnull Project project) {
		if (this.project != null) {
			// If it is possible for this to happen, then this code is broken
			throw new AssertionError("Expected plugin to only be applied once!");
		}
		this.project = project;

		project.apply(ImmutableMap.of("plugin", "eclipse"));
		project.apply(ImmutableMap.of("plugin", "idea"));

		configureEclipse();
		super.apply(project);

		UserDevExtension extension = project.getExtensions().getByType(UserDevExtension.class);

		String baseName = "mod-"
				+ this.project.property("archivesBaseName").toString()
						.toLowerCase();

		TaskContainer tasks = this.project.getTasks();
		final Jar jar = (Jar) tasks.getByName("jar");
		// Note: format is:
		// [baseName]-[appendix]-[version]-[classifier].[extension]
		jar.setBaseName(baseName);
		jar.setAppendix("baseedit");
		jar.setExtension("zip");

		// Disable generation of META-INF.
		// Note that we can't use jar.getMetaInf - that isn't actually a getter,
		// but rather something that creates a subtask.  So steal the field directly.
		try {
			Field metaInfField = Jar.class.getDeclaredField("metaInf");
			metaInfField.setAccessible(true);
			CopySpec metaInf = (CopySpec) metaInfField.get(jar);
			metaInf.exclude("**");  // This is the call that actually disables generation
		} catch (Exception ex) {
			throw new RuntimeException("Failed to disable META-INF generation", ex);
		}

		// Loosely based on AntlrPlugin.  Add new options to each sourceset.
		project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets()
				.all(this::injectSourceSet);

		Configuration minecraft = project.getConfigurations().getByName("minecraft");

		// TODO: In gradle 5.0, there's a named(String, Class<S>)
		@SuppressWarnings({ "unchecked", "rawtypes" })
		TaskProvider<ExtractMCPData> extractSrg = (TaskProvider)project.getTasks().named("extractSrg");
		@SuppressWarnings({ "unchecked", "rawtypes" })
		TaskProvider<GenerateSRG> createMcpToSrg = (TaskProvider)project.getTasks().named("createMcpToSrg");
		TaskProvider<GenerateSRG> createMcpToNotch = project.getTasks().register("createMcpToNotch", GenerateSRG.class);
		TaskProvider<Task> setupDecompProvider = tasks.register("setupDecompWorkspace");

		createMcpToNotch.configure(task -> {
			task.dependsOn(extractSrg);
			task.setNotch(true);
			task.setReverse(true);
			task.setSrg(extractSrg.get().getOutput());
			task.setMappings(extension.getMappings());
		});
		setupDecompProvider.configure(task -> {
			task.doFirst(t -> {
				// Must resolve sources before bin for bin to be a recompiled one
				t.getLogger().lifecycle("Resolving sources jar...");
				getMcSourcesJar();
				t.getLogger().lifecycle("Resolved sources jar.");
				t.getLogger().lifecycle("Resolving bin jar...");
				getMcBinJar();
				t.getLogger().lifecycle("Resolved bin jar.");
			});
		});

		project.afterEvaluate(p -> {
			// Tell it to reobf using the mcp->notch srg
			project.getTasks().withType(RenameJarInPlace.class, task -> {
				task.getDependsOn().remove(createMcpToSrg);
				task.dependsOn(createMcpToNotch);
				task.setMappings(createMcpToNotch.get().getOutput());
			});

			// Prepare for actually finding the sources jar
			List<Dependency> deps = new ArrayList<>(minecraft.getDependencies());
			if (deps.size() != 1) {
				throw new RuntimeException("Expected only one minecraft dependency, but there were " + deps);
			}
			Dependency dep = deps.get(0);
			if (!(dep instanceof ExternalModuleDependency)) {
				throw new RuntimeException("Expected an ExternalModuleDependency, but was a " + dep.getClass() + " (" + dep + ")");
			}

			mcSourcesJarDesc = dep.getGroup() + ":" + dep.getName() + ":" + dep.getVersion() + ":sources";
			mcBinJarDesc = dep.getGroup() + ":" + dep.getName() + ":" + dep.getVersion();
		});
	}

	/**
	 * Injects the needed tasks and such into a {@link SourceSet}.
	 * 
	 * @see org.gradle.api.plugins.antlr.AntlrPlugin#apply Inner class within AntlrPlugin
	 */
	private void injectSourceSet(SourceSet sourceSet) {
		TaskContainer tasks = this.project.getTasks();

		// Add a new "base" virtual directory mapping that can be used within the SourceSet.
		final BaseClassesVirtualDirectory baseDirectoryDelegate = new BaseClassesVirtualDirectory(
				sourceSet);
		new DslObject(sourceSet).getConvention().getPlugins().put(
				BaseClassesVirtualDirectory.NAME, baseDirectoryDelegate);
		// Add the patched source to the all source list
		sourceSet.getAllSource().srcDir(
				baseDirectoryDelegate.getPatchedSourceCallable());

		// Create the new tasks
		String processTaskName = sourceSet.getTaskName("process", "BasePatches");
		String genTaskName = sourceSet.getTaskName("generate", "BasePatches");
		String applyTaskName = sourceSet.getTaskName("apply", "BasePatches");

		TaskProvider<ProcessBasePatchesTask> processTaskProvider = tasks.register(
				processTaskName, ProcessBasePatchesTask.class);

		TaskProvider<GenerateBasePatchesTask> genTaskProvider = tasks.register(
				genTaskName, GenerateBasePatchesTask.class);

		TaskProvider<ApplyBasePatchesTask> applyTaskProvider = tasks.register(
				applyTaskName, ApplyBasePatchesTask.class);

		// Set the default locations for the tasks (so that the user doesn't
		// need to specify them)
		processTaskProvider.configure(processTask -> {
			processTask.setDescription("Processes all of the " + sourceSet.getName()
					+ " base patches, applying and generating them as needed.");

			processTask.setPatches(baseDirectoryDelegate.getPatches());
			processTask.setPatchedSource(baseDirectoryDelegate.getPatchedSource());
			processTask.setOrigJar(getMcSourcesJar());
			processTask.setBaseClasses(baseDirectoryDelegate.getBaseClassesCallable());
		});

		genTaskProvider.configure(genTask -> {
			genTask.setDescription("Generates all of the " + sourceSet.getName()
					+ " base patches, removing any existing ones.");

			genTask.setPatches(baseDirectoryDelegate.getPatches());
			genTask.setPatchedSource(baseDirectoryDelegate.getPatchedSource());
			genTask.setOrigJar(getMcSourcesJar());
			genTask.setBaseClasses(baseDirectoryDelegate.getBaseClassesCallable());
		});

		applyTaskProvider.configure(applyTask -> {
			applyTask.setDescription("Applies all of the " + sourceSet.getName()
					+ " base patches, overwriting any other modified source.");

			applyTask.setPatches(baseDirectoryDelegate.getPatches());
			applyTask.setPatchedSource(baseDirectoryDelegate.getPatchedSource());
			applyTask.setOrigJar(getMcSourcesJar());
			applyTask.setBaseClasses(baseDirectoryDelegate.getBaseClassesCallable());
		});

		// Order the tasks
		// XXX Don't enable this by default for now, due to processing time
		// tasks.getByName(sourceSet.getCompileJavaTaskName()).dependsOn(processTask);
		// TODO: What else do I want?  SourceJar?

		// Tell the java plugin to compile the patched source
		sourceSet.getJava().srcDir(
				baseDirectoryDelegate.getPatchedSourceCallable());
	}

	private File getMcSourcesJar() throws IllegalStateException {
		if (mcSourcesJarDesc == null) {
			throw new IllegalStateException("mcSourcesJarDesc is still null -- getMcSourcesJar actually called before afterEvaluate?");
		}
		if (mcSourcesJar == null) {
			mcSourcesJar = MavenArtifactDownloader.generate(project, mcSourcesJarDesc, false);
		}
		return mcSourcesJar;
	}

	private File getMcBinJar() throws IllegalStateException {
		if (mcBinJarDesc == null) {
			throw new IllegalStateException("mcBinJarDesc is still null -- getMcBinJar actually called before afterEvaluate?");
		}
		if (mcBinJar == null) {
			mcBinJar = MavenArtifactDownloader.generate(project, mcBinJarDesc, false);
		}
		return mcBinJar;
	}

	protected void configureEclipse() {
		EclipseModel eclipseConv = (EclipseModel) project.getExtensions().getByName("eclipse");
		final XmlFileContentMerger classpathMerger = eclipseConv.getClasspath().getFile();
		classpathMerger.whenMerged(obj -> {
			Classpath classpath = (Classpath)obj;
			for (ClasspathEntry entry : classpath.getEntries()) {
				if (entry instanceof SourceFolder) {
					SourceFolder sf = (SourceFolder)entry;
					if (sf.getPath().contains("base")) { // XXX this check is sub-optimal and could have false positives
						sf.getEntryAttributes().put("ignore_optional_problems", "true");
					}
				}
			}
		});
	}
}
