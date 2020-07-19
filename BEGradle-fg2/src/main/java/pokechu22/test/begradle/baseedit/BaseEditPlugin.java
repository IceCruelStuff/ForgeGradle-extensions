package pokechu22.test.begradle.baseedit;

import static net.minecraftforge.gradle.user.UserConstants.*;
import static net.minecraftforge.gradle.common.Constants.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Callable;

import net.minecraftforge.gradle.common.BasePlugin;
import net.minecraftforge.gradle.tasks.RemapSources;
import net.minecraftforge.gradle.user.UserConstants;
import net.minecraftforge.gradle.user.UserVanillaBasePlugin;
import net.minecraftforge.gradle.util.json.version.Version;

import org.gradle.api.Task;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.CopySpec;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.jvm.tasks.Jar;
import org.gradle.plugins.ide.api.XmlFileContentMerger;
import org.gradle.plugins.ide.eclipse.model.Classpath;
import org.gradle.plugins.ide.eclipse.model.ClasspathEntry;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.SourceFolder;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

public class BaseEditPlugin extends
		UserVanillaBasePlugin<BaseEditExtension> {

	@Override
	protected void applyVanillaUserPlugin() {
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

		final Jar sourceJar = (Jar) tasks.getByName("sourceJar");
		sourceJar.setBaseName(baseName);

		// Loosely based on AntlrPlugin.  Add new options to each sourceset.
		project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets()
				.all(this::injectSourceSet);
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

		// XXX should I be depending on this?  Or decomp?  Or another one?
		final RemapSources remapTask = (RemapSources) tasks.getByName(UserConstants.TASK_REMAP);

		// Create the new tasks
		String processTaskName = sourceSet.getTaskName("process", "BasePatches");
		String genTaskName = sourceSet.getTaskName("generate", "BasePatches");
		String applyTaskName = sourceSet.getTaskName("apply", "BasePatches");

		ProcessBasePatchesTask processTask = tasks.create(
				processTaskName, ProcessBasePatchesTask.class);
		processTask.setDescription("Processes all of the " + sourceSet.getName()
				+ " base patches, applying and generating them as needed.");
		processTask.dependsOn(remapTask);

		GenerateBasePatchesTask genTask = tasks.create(
				genTaskName, GenerateBasePatchesTask.class);
		genTask.setDescription("Generates all of the " + sourceSet.getName()
				+ " base patches, removing any existing ones.");
		genTask.dependsOn(remapTask);

		ApplyBasePatchesTask applyTask = tasks.create(
				applyTaskName, ApplyBasePatchesTask.class);
		applyTask.setDescription("Applies all of the " + sourceSet.getName()
				+ " base patches, overwriting any other modified source.");
		applyTask.dependsOn(remapTask);

		// Set the default locations for the tasks (so that the user doesn't
		// need to specify them)
		processTask.setPatches(baseDirectoryDelegate.getPatches());
		processTask.setPatchedSource(baseDirectoryDelegate.getPatchedSource());
		processTask.setOrigJar(new Callable<File>() {
			@Override
			public File call() throws Exception {
				return remapTask.getOutJar();
			}
		});
		processTask.setBaseClasses(baseDirectoryDelegate.getBaseClassesCallable());

		genTask.setPatches(baseDirectoryDelegate.getPatches());
		genTask.setPatchedSource(baseDirectoryDelegate.getPatchedSource());
		genTask.setOrigJar(new Callable<File>() {
			@Override
			public File call() throws Exception {
				return remapTask.getOutJar();
			}
		});
		genTask.setBaseClasses(baseDirectoryDelegate.getBaseClassesCallable());

		applyTask.setPatches(baseDirectoryDelegate.getPatches());
		applyTask.setPatchedSource(baseDirectoryDelegate.getPatchedSource());
		applyTask.setOrigJar(new Callable<File>() {
			@Override
			public File call() throws Exception {
				return remapTask.getOutJar();
			}
		});
		applyTask.setBaseClasses(baseDirectoryDelegate.getBaseClassesCallable());

		// Order the tasks
		tasks.getByName(sourceSet.getCompileJavaTaskName()).dependsOn(processTask);
		// TODO: What else do I want?  SourceJar?

		// Tell the java plugin to compile the patched source
		sourceSet.getJava().srcDir(
				baseDirectoryDelegate.getPatchedSourceCallable());
	}

	@Override
	protected void afterEvaluate() {
		final Jar jarTask = (Jar) project.getTasks().getByName("jar");

		if (this.hasClientRun()) {
			JavaExec exec = (JavaExec) project.getTasks()
					.getByName("runClient");
			// Insert our code into the classpath now, so that
			// it can overwrite vanilla code
			exec.classpath(jarTask.getArchivePath());
		}

		if (this.hasServerRun()) {
			JavaExec exec = (JavaExec) project.getTasks()
					.getByName("runServer");
			// Insert our code into the classpath now, so that
			// it can overwrite vanilla code
			exec.classpath(jarTask.getArchivePath());
		}

		super.afterEvaluate();  // Among other things, sets up the other classpaths

		// Disable the extractAnnotationsJar task; it's not useful for base edits
		Task extractTask = project.getTasks().findByName("extractAnnotationsJar");
		if (extractTask != null) {
			extractTask.setEnabled(false);
		}
	}

	@Override
	protected String getJarName() {
		return "minecraft";
	}

	@Override
	protected void createDecompTasks(String globalPattern, String localPattern) {
		super.makeDecompTasks(globalPattern, localPattern,
				delayedFile(JAR_CLIENT_FRESH), TASK_DL_CLIENT,
				delayedFile(MCP_PATCHES_CLIENT), delayedFile(MCP_INJECT));

		// Ensure we have a version JSON before trying to create the start task
		// since we use the version JSON to determine the main class.
		this.project.getTasks().getByName(TASK_MAKE_START)
				.dependsOn(TASK_DL_VERSION_JSON);
	}

	@Override
	protected boolean hasServerRun() {
		return false;
	}

	@Override
	protected boolean hasClientRun() {
		return true;
	}

	@Override
	protected Object getStartDir() {
		// Use the project cache to not overwrite other configs
		return delayedFile(REPLACE_PROJECT_CACHE_DIR + "/net/minecraft/" + getJarName()
				+ "/" + REPLACE_MC_VERSION + "/start");
	}

	@Override
	protected String getClientTweaker(BaseEditExtension ext) {
		return "";
	}

	@Override
	protected String getClientRunClass(BaseEditExtension ext) {
		// Just directly call the client main class (which also needs to be
		// accessed from a private field...)
		try {
			Field versionField = BasePlugin.class.getDeclaredField("mcVersionJson");
			versionField.setAccessible(true);
			Version version = (Version) versionField.get(this);
			if (version == null) {
				project.getLogger().info("Failed to get version main class as mcVersionJson was null");
				return "net.minecraft.client.main.Main";
			}
			return version.mainClass;
		} catch (Exception e) {
			project.getLogger().warn("Failed to get version main class", e);
			// This is usually the main class
			return "net.minecraft.client.main.Main";
		}
	}

	@Override
	protected String getServerTweaker(BaseEditExtension ext) {
		// Not designed for server use, yet
		return "";
	}

	@Override
	protected String getServerRunClass(BaseEditExtension ext) {
		// Not designed for server use, yet
		return "";
	}

	@Override
	protected List<String> getClientJvmArgs(BaseEditExtension ext) {
		return ext.getResolvedClientJvmArgs();
	}

	@Override
	protected List<String> getServerJvmArgs(BaseEditExtension ext) {
		return ext.getResolvedServerJvmArgs();
	}

	/**
	 * Gets a file, checking if it's up-to-date with an etag.
	 * 
	 * Copied from the superclass, except for a few small modifications...
	 * Basically: STOP TOUCHING THE FILES. It seems that
	 * {@link net.minecraftforge.gradle.tasks.PostDecompileTask
	 * PostDecompileTask} incorrectly reruns when that happens, causing much
	 * slower builds. This means that the 1-minute cooldown no longer applies,
	 * unfortunately.
	 * 
	 * @param strUrl
	 *            The URL of the file to get
	 * @param cache
	 *            The cached version of the file. May not exist.
	 * @param etagFile
	 *            The etag file.
	 * @see net.minecraftforge.gradle.common.BasePlugin#getWithEtag
	 * @author Whoever wrote the base version (not me)
	 */
	@Override
	protected String getWithEtag(String strUrl, File cache, File etagFile) {
		try {
			if (project.getGradle().getStartParameter().isOffline()) {
				// In offline mode, don't even try the internet; always
				// return the cached version.
				return Files.asCharSource(cache, Charsets.UTF_8).read();
			}

			String etag;
			if (etagFile.exists()) {
				etag = Files.asCharSource(etagFile, Charsets.UTF_8).read();
			} else {
				etagFile.getParentFile().mkdirs();
				etag = "";
			}

			URL url = new URL(strUrl);

			HttpURLConnection con = (HttpURLConnection) url.openConnection();
			con.setInstanceFollowRedirects(true);
			con.setRequestProperty("User-Agent", USER_AGENT);
			con.setIfModifiedSince(cache.lastModified());

			if (!Strings.isNullOrEmpty(etag)) {
				con.setRequestProperty("If-None-Match", etag);
			}

			con.connect();

			String out = null;
			if (con.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
				// 304 Not Modified - use the etag'd version
				out = Files.asCharSource(cache, Charsets.UTF_8).read();
			} else if (con.getResponseCode() == HttpURLConnection.HTTP_OK) {
				// 200 OK
				InputStream stream = con.getInputStream();
				byte[] data = ByteStreams.toByteArray(stream);
				Files.write(data, cache);
				stream.close();

				// Write the etag, if present
				etag = con.getHeaderField("ETag");
				if (!Strings.isNullOrEmpty(etag)) {
					Files.asCharSink(etagFile, Charsets.UTF_8).write(etag);
				}

				out = new String(data);
			} else {
				project.getLogger().error(
						"Etag download for " + strUrl + " failed with code "
								+ con.getResponseCode());
			}

			con.disconnect();

			return out;
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (cache.exists()) {
			try {
				return Files.asCharSource(cache, Charsets.UTF_8).read();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		throw new RuntimeException("Unable to obtain url (" + strUrl
				+ ") with etag!");
	}

	@Override
	protected void configureEclipse() {
		super.configureEclipse();

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
