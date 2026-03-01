import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dslplatform.json.DslJson;
import com.jsoniter.JsonIterator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.squareup.moshi.JsonReader;
import jakarta.json.spi.JsonProvider;
import okio.Buffer;
import org.everit.json.schema.Schema;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONParserConfiguration;
import org.json.JSONTokener;
import org.json.JSONObject;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

public final class BenchTargets {

  public record BenchTarget(String name, Function<String, Boolean> func) {}

  public static final List<BenchTarget> ACTIVE_TARGETS = List.of(
      new BenchTarget("Jackson", BenchTargets::jackson),
      new BenchTarget("Gson", BenchTargets::gsonValidate),
      new BenchTarget("OrgJson", BenchTargets::orgJsonValidate),
      new BenchTarget("Jsonp", BenchTargets::jsonpValidate),
      new BenchTarget("Moshi", BenchTargets::moshiValidate),
      new BenchTarget("Networknt", BenchTargets::networkntValidate),
      new BenchTarget("Validason", Validason::isValid)
  );

  private static final StringBuilder SB = new StringBuilder(100000);

  private static final JsonFactory FACTORY_CANON_OFF = JsonFactory.builder()
      .configure(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES, false)
      .build();

  private static final JsonFactory FACTORY_DEFAULT = JsonFactory.builder().build();

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JSONParserConfiguration ORGJSON_CONFIG = new JSONParserConfiguration()
      .withStrictMode(true)
      .withOverwriteDuplicateKey(true);
  private static final JsonProvider JSONP_PROVIDER = JsonProvider.provider();
  private static final DslJson<Object> DSL_JSON = new DslJson<>(new DslJson.Settings<>().includeServiceLoader());
  private static final JsonSchemaFactory NETWORKNT_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final JsonSchema NETWORKNT_SCHEMA = NETWORKNT_FACTORY.getSchema(
      "{\"type\":[\"object\",\"array\",\"string\",\"number\",\"integer\",\"boolean\",\"null\"]}"
  );
  private static final Schema EVERIT_SCHEMA = SchemaLoader.load(
      new JSONObject("{\"type\":[\"object\",\"array\",\"string\",\"number\",\"integer\",\"boolean\",\"null\"]}")
  );

  private BenchTargets() {}

  public static boolean jackson(String json) {
    return jacksonCurrent(json);
  }

  public static boolean jacksonCurrent(String json) {
    return parseWithFactory(FACTORY_CANON_OFF, json);
  }

  public static boolean jacksonCanonOff(String json) {
    return parseWithFactory(FACTORY_CANON_OFF, json);
  }

  public static boolean jacksonDefaultCanonOn(String json) {
    return parseWithFactory(FACTORY_DEFAULT, json);
  }

  public static boolean jacksonReadTree(String json) {
    try {
      OBJECT_MAPPER.readTree(json);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean gsonValidate(String json) {
    try {
      com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new StringReader(json));
      reader.setStrictness(com.google.gson.Strictness.STRICT);
      com.google.gson.JsonParser.parseReader(reader);
      return reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT;
    } catch (Throwable t) {
      return false;
    }
  }

  public static boolean orgJsonValidate(String json) {
    try {
      parseOrgJsonStrict(json);
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public static boolean jsonpValidate(String json) {
    try (jakarta.json.stream.JsonParser parser = JSONP_PROVIDER.createParser(new StringReader(json))) {
      while (parser.hasNext()) {
        parser.next();
      }
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public static boolean moshiValidate(String json) {
    try {
      JsonReader reader = JsonReader.of(new Buffer().writeUtf8(json));
      reader.setLenient(false);
      reader.readJsonValue();
      return reader.peek() == JsonReader.Token.END_DOCUMENT;
    } catch (Throwable t) {
      return false;
    }
  }

  public static boolean dslJsonValidate(String json) {
    try {
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      var reader = DSL_JSON.newReader(bytes);
      reader.getNextToken();
      reader.skip();
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public static boolean jsoniterValidate(String json) {
    try {
      JsonIterator.deserialize(json);
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public static boolean networkntValidate(String json) {
    try {
      return NETWORKNT_SCHEMA.validate(OBJECT_MAPPER.readTree(json)).isEmpty();
    } catch (Throwable t) {
      return false;
    }
  }

  public static boolean everitValidate(String json) {
    try {
      EVERIT_SCHEMA.validate(parseOrgJsonStrict(json));
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  private static boolean parseWithFactory(JsonFactory factory, String json) {
    try (JsonParser parser = factory.createParser(json)) {
      while (parser.nextToken() != null) {
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static Object parseOrgJsonStrict(String json) {
    JSONTokener tokener = new JSONTokener(json, ORGJSON_CONFIG);
    Object value = tokener.nextValue();
    char trailing = tokener.nextClean();
    if (trailing != 0) {
      throw new IllegalArgumentException("Trailing non-whitespace content");
    }
    return value;
  }

  private static String alwaysEscape(String s) {
    SB.setLength(0);
    SB.append('"');
    int length = s.length();
    for (int i = 0; i < length; i++) {
      char c = s.charAt(i);
      if (c > '\\') {
        SB.append(c);
      } else if (c > '"' && c < '\\') {
        SB.append(c);
      } else if (c >= ' ' && c < '"') {
        SB.append(c);
      } else if (c == '"') {
        SB.append("\\\"");
      } else if (c == '\\') {
        SB.append("\\\\");
      } else {
        if (c == '\b') {
          SB.append("\\b");
        } else if (c == '\f') {
          SB.append("\\f");
        } else if (c == '\n') {
          SB.append("\\n");
        } else if (c == '\r') {
          SB.append("\\r");
        } else if (c == '\t') {
          SB.append("\\t");
        } else {
          SB.append("\\u");
          int n = c;
          for (int j = 0; j < 4; j++) {
            SB.append(toHexChar((n & 0xf000) >> 12));
            n <<= 4;
          }
        }
      }
    }
    SB.append('"');
    return SB.toString();
  }

  private static char toHexChar(int digit) {
    return (char) (digit < 10 ? ('0' + digit) : ('a' + digit - 10));
  }
}
