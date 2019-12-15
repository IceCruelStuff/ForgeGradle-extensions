package pokechu22.test.begradle.customsrg;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import net.minecraftforge.gradle.tasks.GenSrgs;
import net.minecraftforge.srg2source.rangeapplier.MethodData;
import net.minecraftforge.srg2source.rangeapplier.SrgContainer;
import pokechu22.test.begradle.customsrg.ExtraSrgUtil;

import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.OutputFiles;
import org.gradle.api.tasks.TaskAction;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

/**
 * Handles secondary SRGs, _and_ creates reverse SRGs.
 */
public class GenSrgsWithCustomSupportTask extends GenSrgs {
	protected boolean hasCopied = false;
	protected boolean hasCustomSrgs = false;

	private Object srgLog;
	/**
	 * A file that lists the SRGs that were used, for reference (and caching?) purposes.
	 * @return The SRG log.
	 */
	@OutputFile
	public File getSrgLog() {
		return getProject().file(srgLog);
	}
	/**
	 * A file that lists the SRGs that were used, for reference purposes.
	 * @param srgLog The file to log to
	 */
	public void setSrgLog(Object srgLog) {
		this.srgLog = srgLog;
	}

	private Object reverseSrg;
	/**
	 * A SRG that reverses the extra SRGs.
	 * @return The reverse SRG
	 */
	@OutputFile
	public File getReverseSrg() {
		return getProject().file(reverseSrg);
	}
	/**
	 * A SRG that reverses the extra SRGs.
	 * @param reverseSrg The file to write the reverse SRG to.
	 */
	public void setReverseSrg(Object reverseSrg) {
		this.reverseSrg = reverseSrg;
	}

	private Object extraSrgs;
	/**
	 * Gets the auxiliary SRGs provided to this task.
	 * @return The extra SRGs.
	 */
	@Override
	@OutputFiles
	public FileCollection getExtraSrgs() {
		return getProject().files(extraSrgs);
	}
	/**
	 * Sets the auxiliary SRGs provided to this task.
	 * @param extraSrgs The extra SRG files.
	 */
	public void setExtraSrgs(Object extraSrgs) {
		this.extraSrgs = extraSrgs;
	}
	@Override
	@Deprecated
	public void addExtraSrg(File file) {
		throw new RuntimeException("addExtraSrg doesn't work; use the extraSrgs block");
	}

	private Object remappedInExc;
	/**
	 * Gets the location to save the remapped version of the in EXC.
	 * @return The remapped in EXC.
	 */
	public File getRemappedInExc() {
		return getProject().file(remappedInExc);
	}
	/**
	 * Sets the location to save the remapped version of the in EXC.
	 * @param remappedInExc The remapped in EXC.
	 */
	public void setRemappedInExc(Object remappedInExc) {
		this.remappedInExc = remappedInExc;
	}

	/**
	 * Copy settings from the previous version of this task.
	 *
	 * @param oldTask The original genSrgs task
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected void copyFrom(GenSrgs oldTask) {
		getLogger().debug("Copying from " + oldTask);

		hasCopied = true;
		try {
			for (Field field : GenSrgs.class.getDeclaredFields()) {
				if (Modifier.isFinal(field.getModifiers())) {
					if (List.class.isAssignableFrom(field.getType())) {
						field.setAccessible(true);
						List oldList = (List) field.get(oldTask);
						List myList = (List) field.get(this);
						myList.addAll(oldList);
					} else {
						throw new RuntimeException(
								"Don't know what to do with final field "
										+ field);
					}
					continue;
				} else {
					field.setAccessible(true);
					Object value = field.get(oldTask);
					field.set(this, value);
				}
			}
		} catch (IllegalAccessException | SecurityException ex) {
			throw new RuntimeException("Failed to copy fields from " + oldTask, ex);
		}

		this.setDoesCache(oldTask.doesCache());
		this.setDependsOn(oldTask.getDependsOn());
	}

	/** Overrides original task action. */
	@Override
	public void doTask() throws IOException {
		if (!hasCopied) {
			getLogger().warn("Did not copy settings for custom SRG task; this is needed for the task to do anything.");
		}

		// Begin modified quote from original
        // csv data.  SRG -> MCP
        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        readCSVs(getMethodsCsv(), getFieldsCsv(), methods, fields);

        // Do SRG stuff
        SrgContainer extraSrg = new SrgContainer();
		for (File file : getExtraSrgs()) {
			extraSrg.readSrg(file);
		}
        SrgContainer inSrg = new SrgContainer().readSrg(getInSrg());
        SrgContainer newSrg = ExtraSrgUtil.remapSrg(inSrg, extraSrg);
        writeOutSrgs(newSrg, methods, fields);

        // do EXC stuff
        this.makeRemappedInExc(extraSrg);
        writeOutExcs(newSrg, Collections.emptyMap(), methods);

		// End quote
	}

	private static final Method readCSVs, writeOutSrgs, writeOutExcs;
	static {
		try {
			Class<GenSrgs> clazz = GenSrgs.class;
			readCSVs = clazz.getDeclaredMethod("readCSVs", File.class, File.class, Map.class, Map.class);
			readCSVs.setAccessible(true);
			writeOutSrgs = clazz.getDeclaredMethod("writeOutSrgs", SrgContainer.class, Map.class, Map.class);
			writeOutSrgs.setAccessible(true);
			writeOutExcs = clazz.getDeclaredMethod("writeOutExcs", SrgContainer.class, Map.class, Map.class);
			writeOutExcs.setAccessible(true);
		} catch (NoSuchMethodException | SecurityException ex) {
			throw new AssertionError("Failed to find/access private methods", ex);
		}
	}
	protected void readCSVs(File methodCsv, File fieldCsv, Map<String, String> methodMap, Map<String, String> fieldMap) {
		try {
			// The original readCSVs was static, thus null is used
			readCSVs.invoke(null, methodCsv, fieldCsv, methodMap, fieldMap);
		} catch (IllegalAccessException | IllegalArgumentException ex) {
			throw new AssertionError("Failed to invoke readCSVs", ex);
		} catch (InvocationTargetException ex) {
			throw new RuntimeException("Failed to invoke readCSVs", ex);
		}
	}
	protected void writeOutSrgs(SrgContainer inSrg, Map<String, String> methods, Map<String, String> fields) throws IOException {
		try {
			writeOutSrgs.invoke(this, inSrg, methods, fields);
		} catch (IllegalAccessException | IllegalArgumentException ex) {
			throw new AssertionError("Failed to invoke writeOutSrgs", ex);
		} catch (InvocationTargetException ex) {
			throw new RuntimeException("Failed to invoke readwriteOutSrgsCSVs", ex);
		}
	}

	// Hack -- super.writeOutExcs uses getInExc; redirect it when needed.
	private boolean writingExcs = false;
	@Override
	public File getInExc() {
		if (this.writingExcs) {
			return this.getRemappedInExc();
		}
		return super.getInExc();
	}

	protected void writeOutExcs(SrgContainer inSrg, Map<String, String> excRemap, Map<String, String> methods) throws IOException {
		try {
			this.writingExcs = true;
			writeOutExcs.invoke(this, inSrg, excRemap, methods);
			this.writingExcs = false;
		} catch (IllegalAccessException | IllegalArgumentException ex) {
			throw new AssertionError("Failed to invoke writeOutExcs", ex);
		} catch (InvocationTargetException ex) {
			throw new RuntimeException("Failed to invoke writeOutExcs", ex);
		}
	}

	/**
	 * Remaps the contents of the EXC file, based on the SRG.
	 *
	 * @param extraSrg The extra SRG. Only its {@link SrgContainer#classMap
	 *                 classMap} is used.
	 * @throws IOException when an IO error occurs.
	 */
	protected void makeRemappedInExc(SrgContainer extraSrg) throws IOException {
		List<String> inLines = Files.readLines(super.getInExc(), Charsets.UTF_8);
		List<String> outLines = ExtraSrgUtil.remapExc(inLines, extraSrg);

		try (BufferedWriter out = Files.newWriter(getRemappedInExc(), Charsets.UTF_8)) {
			for (String line : outLines) {
				out.write(line);
				out.newLine();
			}
		}
	}

	/**
	 * The second task action, which handles the SRG log and the reverse SRG.
	 * @throws IOException if an IO error occurs.
	 */
	@TaskAction
	public void handleTask() throws IOException {
		writeSrgLog();
		writeReverseSrg();
	}

	protected void writeSrgLog() throws IOException {
		try (BufferedWriter writer = Files.newWriter(getSrgLog(), Charsets.UTF_8)) {
			FileCollection extraSrgs = getExtraSrgs();
			writer.write("Extra SRGs:");
			writer.newLine();
			for (File file : extraSrgs) {
				writer.write(file.getCanonicalPath());
				writer.newLine();
			}
		}
	}

	protected void writeReverseSrg() throws IOException {
		// Convert to a list so that we can reverse it.
		List<File> extraSrgs = new ArrayList<>(getExtraSrgs().getFiles());
		Collections.reverse(extraSrgs);
		// We need to reverse the list to reverse priorities; thus, duplicate entries
		// are handled in the opposite order.  Well, technically that behavior may be
		// undefined, but it seems better to do this.
		SrgContainer srg = new SrgContainer();
		for (File file : extraSrgs) {
			srg.readSrg(file);
		}

		// We don't need to reverse the _order_ of the entries since a map can only
		// have one key; thus it doesn't matter.  But we _do_ need to reverse the order
		// of the elements (value -> key).

		try (BufferedWriter writer = Files.newWriter(getReverseSrg(), Charsets.UTF_8)) {
			// Packages
			for (Entry<String, String> e : srg.packageMap.entrySet()) {
				writer.write("PK: " + e.getValue() + " " + e.getKey());
				writer.newLine();
			}
			// Classes
			for (Entry<String, String> e : srg.classMap.entrySet()) {
				writer.write("CL: " + e.getValue() + " " + e.getKey());
				writer.newLine();
			}
			// Fields
			for (Entry<String, String> e : srg.fieldMap.entrySet()) {
				writer.write("FD: " + e.getValue() + " " + e.getKey());
				writer.newLine();
			}
			// Methods
			for (Entry<MethodData, MethodData> e : srg.methodMap.entrySet()) {
				// toString handles the format automatically
				writer.write("MD: " + e.getValue() + " " + e.getKey());
				writer.newLine();
			}
		}
	}
}
