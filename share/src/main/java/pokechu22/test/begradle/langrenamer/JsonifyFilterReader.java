package pokechu22.test.begradle.langrenamer;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * A FilterReader that converts properties language files to JSON.
 */
public class JsonifyFilterReader extends FilterReader {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public JsonifyFilterReader(Reader in) throws IOException {
		super(jsonify(in));
	}

	private static StringReader jsonify(Reader in) throws IOException {
		Map<String, String> map = getTranslations(in);
		String json = GSON.toJson(map);
		return new StringReader(json);
	}

	// These should maybe be configurable, but there isn't a good way of getting
	// that information to here. (Gradle doesn't let you use a custom constructor
	// when using a FilterReader)
	private static final String CONVERSION_NOTE_KEY = "__conversionNote";
	private static final String CONVERSION_NOTE = "!!! THIS IS A GENERATED FILE !!! "
			+ "If you plan on editing it, use the original .lang file instead. "
			+ "It can be found on GitHub, or in version 1.12.2 and earlier. "
			+ "These json lang files lack comments and have a random order, "
			+ "so the older format is used as the base and these are generated.";
	
	private static Map<String, String> getTranslations(Reader in) throws IOException {
		Properties properties = new Properties();
		properties.load(in);
		Map<String, String> result = new LinkedHashMap<>();
		// Put this first (we have control over that, even though the order of the
		// Properties is messed up and out of our control)
		result.put(CONVERSION_NOTE_KEY, CONVERSION_NOTE);
		// Properties is a HashTable, and it can be contaminated with non-string things
		// because it was designed poorly :|
		for (Map.Entry<Object, Object> entry : properties.entrySet()) {
			String key = (String)entry.getKey();
			String value = ((String)entry.getValue()).replaceAll("\n", "\\\\n");
			result.put(key, value);
		}
		return result;
	}
}
