package io.validason;

/**
 * High-performance JSON string validator.
 *
 * <pre>{@code
 * Validason.isValid("{\"name\":\"John\"}");  // true
 * Validason.isValid("{invalid}");            // false
 * }</pre>
 *
 * @see <a href="https://github.com/kyu4583/validason">GitHub</a>
 * @since 1.0.2
 */
public final class Validason {

  private Validason() {}

  private static final int THRESHOLD = 80;
  private static final byte ARR_FIRST     = 0;
  private static final byte ARR_NEXT      = 1;
  private static final byte ARR_AFTER     = 2;
  private static final byte OBJ_FIRST     = 3;
  private static final byte OBJ_NEXT      = 4;
  private static final byte OBJ_AFTER_KEY = 5;
  private static final byte OBJ_EXPECT_VAL = 6;
  private static final byte OBJ_AFTER_VAL = 7;

  private static final byte[] SHARED_HEX_TABLE;
  static {
    byte[] table = new byte[128];
    java.util.Arrays.fill(table, (byte) -1);
    for (int c = '0'; c <= '9'; c++) table[c] = (byte) (c - '0');
    for (int c = 'a'; c <= 'f'; c++) table[c] = (byte) (c - 'a' + 10);
    for (int c = 'A'; c <= 'F'; c++) table[c] = (byte) (c - 'A' + 10);
    SHARED_HEX_TABLE = table;
  }

  /**
   * Returns whether the given string is valid JSON
   * as defined by <a href="https://www.rfc-editor.org/rfc/rfc8259">RFC 8259</a>.
   *
   * @param s the string to validate, may be {@code null}
   * @return {@code true} if the string is valid JSON, {@code false} otherwise
   *         (including {@code null} and empty string)
   */
  public static boolean isValid(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    if (s.length() < THRESHOLD) {
      return PathShort.isValid(s);
    }
    return PathLong.isValid(s);
  }

  private static final class PathShort {

    private PathShort() {}

    private static final int INITIAL_STACK_CAP = 32;

    private static final class Ctx {
      final String s;
      final int n;
      int p;
      byte[] stack = new byte[INITIAL_STACK_CAP];
      int depth;

      Ctx(String s) {
        this.s = s;
        this.n = s.length();
      }
    }

    public static boolean isValid(String s) {
      Ctx ctx = new Ctx(s);

      skipWs(ctx);
      if (ctx.p >= ctx.n) {
        return false;
      }

      char c0 = ch(ctx);
      if (c0 == '{') {
        ctx.p++;
        pushState(ctx, OBJ_FIRST);
      } else if (c0 == '[') {
        ctx.p++;
        pushState(ctx, ARR_FIRST);
      } else {
        if (!parsePrimitive(ctx)) {
          return false;
        }
        skipWs(ctx);
        return ctx.p == ctx.n;
      }

      boolean valid = true;
      while (valid && ctx.depth > 0) {
        skipWs(ctx);
        if (ctx.p >= ctx.n) {
          valid = false;
          break;
        }

        int top = ctx.depth - 1;
        byte st = ctx.stack[top];

        if (st <= ARR_AFTER) {
          valid = handleArray(ctx, top, st);
        } else {
          valid = handleObject(ctx, top, st);
        }
      }

      if (!valid) {
        return false;
      }
      skipWs(ctx);
      return ctx.p == ctx.n;
    }

    private static boolean handleArray(Ctx ctx, int top, byte st) {
      if (st == ARR_FIRST) {
        if (ch(ctx) == ']') {
          ctx.p++;
          ctx.depth--;
          return true;
        }
        return parseValue(ctx, top, ARR_AFTER);
      }
      if (st == ARR_NEXT) {
        return parseValue(ctx, top, ARR_AFTER);
      }
      char c = ch(ctx);
      if (c == ']') {
        ctx.p++;
        ctx.depth--;
        return true;
      }
      if (c == ',') {
        ctx.p++;
        ctx.stack[top] = ARR_NEXT;
        return true;
      }
      return false;
    }

    private static boolean handleObject(Ctx ctx, int top, byte st) {
      if (st == OBJ_FIRST) {
        if (ch(ctx) == '}') {
          ctx.p++;
          ctx.depth--;
          return true;
        }
        if (ch(ctx) != '"' || !parseString(ctx)) {
          return false;
        }
        ctx.stack[top] = OBJ_AFTER_KEY;
        return true;
      }
      if (st == OBJ_NEXT) {
        if (ch(ctx) != '"' || !parseString(ctx)) {
          return false;
        }
        ctx.stack[top] = OBJ_AFTER_KEY;
        return true;
      }
      if (st == OBJ_AFTER_KEY) {
        if (ch(ctx) != ':') {
          return false;
        }
        ctx.p++;
        ctx.stack[top] = OBJ_EXPECT_VAL;
        return true;
      }
      if (st == OBJ_EXPECT_VAL) {
        return parseValue(ctx, top, OBJ_AFTER_VAL);
      }
      char c = ch(ctx);
      if (c == '}') {
        ctx.p++;
        ctx.depth--;
        return true;
      }
      if (c == ',') {
        ctx.p++;
        ctx.stack[top] = OBJ_NEXT;
        return true;
      }
      return false;
    }

    private static boolean parseValue(Ctx ctx, int top, byte afterState) {
      char c = ch(ctx);
      if (c == '{') {
        ctx.stack[top] = afterState;
        ctx.p++;
        pushState(ctx, OBJ_FIRST);
        return true;
      }
      if (c == '[') {
        ctx.stack[top] = afterState;
        ctx.p++;
        pushState(ctx, ARR_FIRST);
        return true;
      }
      if (parsePrimitive(ctx)) {
        ctx.stack[top] = afterState;
        return true;
      }
      return false;
    }

    private static void skipWs(Ctx ctx) {
      while (ctx.p < ctx.n) {
        switch (ch(ctx)) {
          case ' ':
          case '\n':
          case '\r':
          case '\t':
            ctx.p++;
            break;
          default:
            return;
        }
      }
    }

    private static void pushState(Ctx ctx, byte state) {
      if (ctx.depth == ctx.stack.length) {
        int newCap = ctx.depth + INITIAL_STACK_CAP;
        ctx.stack = java.util.Arrays.copyOf(ctx.stack, newCap);
      }
      ctx.stack[ctx.depth++] = state;
    }

    private static boolean parsePrimitive(Ctx ctx) {
      char c = ch(ctx);

      if (c == '"') {
        return parseString(ctx);
      }
      if (c == 't') {
        return parseLiteral(ctx, 'r', 'u', 'e', 4);
      }
      if (c == 'f') {
        return parseLiteral(ctx, 'a', 'l', 's', 5);
      }
      if (c == 'n') {
        return parseLiteral(ctx, 'u', 'l', 'l', 4);
      }
      if (c == '-' || (c >= '0' && c <= '9')) {
        return parseNumber(ctx);
      }
      return false;
    }

    private static boolean parseLiteral(Ctx ctx, char c1, char c2, char c3, int len) {
      if (ctx.p + len > ctx.n) {
        return false;
      }
      if (ch(ctx, 1) != c1 || ch(ctx, 2) != c2 || ch(ctx, 3) != c3) {
        return false;
      }
      if (len == 5 && ch(ctx, 4) != 'e') {
        return false;
      }
      ctx.p += len;
      return true;
    }

    private static boolean parseString(Ctx ctx) {
      ctx.p++;
      while (ctx.p < ctx.n) {
        while (ctx.p < ctx.n) {
          char c = ch(ctx);
          if (c == '"' || c == '\\' || c < 0x20) {
            break;
          }
          ctx.p++;
        }
        if (ctx.p >= ctx.n) {
          return false;
        }

        char c = ch(ctx);
        if (c == '"') {
          ctx.p++;
          return true;
        }
        if (c == '\\') {
          ctx.p++;
          if (ctx.p >= ctx.n) {
            return false;
          }
          char e = ch(ctx);
          if (e == '"' || e == '\\' || e == '/' || e == 'b' ||
                  e == 'f' || e == 'n' || e == 'r' || e == 't') {
            ctx.p++;
          } else if (e == 'u') {
            ctx.p++;
            int cp = parseUnicodeEscape(ctx);
            if (cp < 0) {
              return false;
            }
            if (cp >= 0xD800 && cp <= 0xDBFF) {
              if (ctx.p + 1 >= ctx.n || ch(ctx) != '\\' || ch(ctx, 1) != 'u') {
                return false;
              }
              ctx.p += 2;
              int lo = parseUnicodeEscape(ctx);
              if (lo < 0xDC00 || lo > 0xDFFF) {
                return false;
              }
            } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
              return false;
            }
          } else {
            return false;
          }
        } else {
          if (c < 0x20) {
            return false;
          }
          ctx.p++;
        }
      }
      return false;
    }

    private static int parseUnicodeEscape(Ctx ctx) {
      if (ctx.p + 4 > ctx.n) {
        return -1;
      }
      int v = 0;
      for (int i = 0; i < 4; i++) {
        char c = ch(ctx, i);
        int h;
        if (c >= '0' && c <= '9') {
          h = c - '0';
        } else if (c >= 'a' && c <= 'f') {
          h = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
          h = c - 'A' + 10;
        } else {
          return -1;
        }
        v = (v << 4) | h;
      }
      ctx.p += 4;
      return v;
    }

    private static boolean parseNumber(Ctx ctx) {
      if (ch(ctx) == '-') {
        ctx.p++;
        if (ctx.p >= ctx.n) {
          return false;
        }
      }

      char c0 = ch(ctx);
      if (c0 == '0') {
        ctx.p++;
        if (ctx.p < ctx.n && ch(ctx) >= '0' && ch(ctx) <= '9') {
          return false;
        }
      } else if (c0 >= '1' && c0 <= '9') {
        ctx.p++;
        while (ctx.p < ctx.n && ch(ctx) >= '0' && ch(ctx) <= '9') {
          ctx.p++;
        }
      } else {
        return false;
      }

      if (ctx.p < ctx.n && ch(ctx) == '.') {
        ctx.p++;
        if (ctx.p >= ctx.n || ch(ctx) < '0' || ch(ctx) > '9') {
          return false;
        }
        ctx.p++;
        while (ctx.p < ctx.n && ch(ctx) >= '0' && ch(ctx) <= '9') {
          ctx.p++;
        }
      }

      if (ctx.p < ctx.n && (ch(ctx) == 'e' || ch(ctx) == 'E')) {
        ctx.p++;
        if (ctx.p >= ctx.n) {
          return false;
        }
        if (ch(ctx) == '+' || ch(ctx) == '-') {
          ctx.p++;
          if (ctx.p >= ctx.n) {
            return false;
          }
        }
        if (ch(ctx) < '0' || ch(ctx) > '9') {
          return false;
        }
        ctx.p++;
        while (ctx.p < ctx.n && ch(ctx) >= '0' && ch(ctx) <= '9') {
          ctx.p++;
        }
      }
      return true;
    }

    private static char ch(Ctx ctx) {
      return ctx.s.charAt(ctx.p);
    }

    private static char ch(Ctx ctx, int offset) {
      return ctx.s.charAt(ctx.p + offset);
    }
  }

  private static final class PathLong {

    private PathLong() {}

    private static final int INITIAL_STACK_CAP = 24;

    private static final byte[] HEX_TABLE = SHARED_HEX_TABLE;

    private static final ThreadLocal<char[]> TL_BUF = ThreadLocal.withInitial(() -> new char[4096]);

    private static final class Ctx {
      final char[] buf;
      final int n;
      int p;
      byte[] stack = new byte[INITIAL_STACK_CAP];
      int depth;

      Ctx(String s) {
        this.n = s.length();
        this.buf = acquireBuffer(this.n);
        s.getChars(0, this.n, this.buf, 0);
      }
    }

    public static boolean isValid(String s) {
      Ctx ctx = new Ctx(s);

      skipWs(ctx);
      if (ctx.p >= ctx.n) {
        return false;
      }

      char c0 = ch(ctx);
      if (c0 == '{') {
        ctx.p++;
        pushState(ctx, OBJ_FIRST);
      } else if (c0 == '[') {
        ctx.p++;
        pushState(ctx, ARR_FIRST);
      } else {
        if (!parsePrimitive(ctx)) {
          return false;
        }
        skipWs(ctx);
        return ctx.p == ctx.n;
      }

      boolean valid = true;
      while (valid && ctx.depth > 0) {
        skipWs(ctx);
        if (ctx.p >= ctx.n) {
          valid = false;
          break;
        }

        int top = ctx.depth - 1;
        byte st = ctx.stack[top];

        if (st <= ARR_AFTER) {
          valid = handleArray(ctx, top, st);
        } else {
          valid = handleObject(ctx, top, st);
        }
      }

      if (!valid) {
        return false;
      }
      skipWs(ctx);
      return ctx.p == ctx.n;
    }

    private static boolean handleArray(Ctx ctx, int top, byte st) {
      if (st == ARR_FIRST) {
        if (ch(ctx) == ']') {
          ctx.p++;
          ctx.depth--;
          return true;
        }
        return parseValue(ctx, top, ARR_AFTER);
      }
      if (st == ARR_NEXT) {
        return parseValue(ctx, top, ARR_AFTER);
      }
      char c = ch(ctx);
      if (c == ']') {
        ctx.p++;
        ctx.depth--;
        return true;
      }
      if (c == ',') {
        ctx.p++;
        ctx.stack[top] = ARR_NEXT;
        return true;
      }
      return false;
    }

    private static boolean handleObject(Ctx ctx, int top, byte st) {
      if (st == OBJ_FIRST) {
        if (ch(ctx) == '}') {
          ctx.p++;
          ctx.depth--;
          return true;
        }
        if (ch(ctx) != '"' || !parseString(ctx)) {
          return false;
        }
        ctx.stack[top] = OBJ_AFTER_KEY;
        return true;
      }
      if (st == OBJ_NEXT) {
        if (ch(ctx) != '"' || !parseString(ctx)) {
          return false;
        }
        ctx.stack[top] = OBJ_AFTER_KEY;
        return true;
      }
      if (st == OBJ_AFTER_KEY) {
        if (ch(ctx) != ':') {
          return false;
        }
        ctx.p++;
        ctx.stack[top] = OBJ_EXPECT_VAL;
        return true;
      }
      if (st == OBJ_EXPECT_VAL) {
        return parseValue(ctx, top, OBJ_AFTER_VAL);
      }
      char c = ch(ctx);
      if (c == '}') {
        ctx.p++;
        ctx.depth--;
        return true;
      }
      if (c == ',') {
        ctx.p++;
        ctx.stack[top] = OBJ_NEXT;
        return true;
      }
      return false;
    }

    private static boolean parseValue(Ctx ctx, int top, byte afterState) {
      char c = ch(ctx);
      if (c == '{') {
        ctx.stack[top] = afterState;
        ctx.p++;
        pushState(ctx, OBJ_FIRST);
        return true;
      }
      if (c == '[') {
        ctx.stack[top] = afterState;
        ctx.p++;
        pushState(ctx, ARR_FIRST);
        return true;
      }
      if (parsePrimitive(ctx)) {
        ctx.stack[top] = afterState;
        return true;
      }
      return false;
    }

    private static void skipWs(Ctx ctx) {
      while (ctx.p < ctx.n) {
        switch (ch(ctx)) {
          case ' ':
          case '\n':
          case '\r':
          case '\t':
            ctx.p++;
            break;
          default:
            return;
        }
      }
    }

    private static void pushState(Ctx ctx, byte state) {
      if (ctx.depth == ctx.stack.length) {
        int newCap = ctx.depth + INITIAL_STACK_CAP;
        ctx.stack = java.util.Arrays.copyOf(ctx.stack, newCap);
      }
      ctx.stack[ctx.depth++] = state;
    }

    private static boolean parsePrimitive(Ctx ctx) {
      char c = ch(ctx);

      if (c == '"') {
        return parseString(ctx);
      }
      if (c == 't') {
        return parseLiteral(ctx, 'r', 'u', 'e', 4);
      }
      if (c == 'f') {
        return parseLiteral(ctx, 'a', 'l', 's', 5);
      }
      if (c == 'n') {
        return parseLiteral(ctx, 'u', 'l', 'l', 4);
      }
      if (c == '-' || (c >= '0' && c <= '9')) {
        return parseNumber(ctx);
      }
      return false;
    }

    private static boolean parseLiteral(Ctx ctx, char c1, char c2, char c3, int len) {
      if (ctx.p + len > ctx.n) {
        return false;
      }
      if (ch(ctx, 1) != c1 || ch(ctx, 2) != c2 || ch(ctx, 3) != c3) {
        return false;
      }
      if (len == 5 && ch(ctx, 4) != 'e') {
        return false;
      }
      ctx.p += len;
      return true;
    }

    private static boolean parseString(Ctx ctx) {
      ctx.p++;
      while (ctx.p < ctx.n) {
        char c = ch(ctx);
        if (c == '"') {
          ctx.p++;
          return true;
        }
        if (c == '\\') {
          ctx.p++;
          if (ctx.p >= ctx.n) {
            return false;
          }
          char e = ch(ctx);
          if (e == '"' || e == '\\' || e == '/' || e == 'b' ||
                  e == 'f' || e == 'n' || e == 'r' || e == 't') {
            ctx.p++;
          } else if (e == 'u') {
            ctx.p++;
            int cp = parseUnicodeEscape(ctx);
            if (cp < 0) {
              return false;
            }
            if (cp >= 0xD800 && cp <= 0xDBFF) {
              if (ctx.p + 1 >= ctx.n || ch(ctx) != '\\' || ch(ctx, 1) != 'u') {
                return false;
              }
              ctx.p += 2;
              int lo = parseUnicodeEscape(ctx);
              if (lo < 0xDC00 || lo > 0xDFFF) {
                return false;
              }
            } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
              return false;
            }
          } else {
            return false;
          }
        } else {
          if (c < 0x20) {
            return false;
          }
          ctx.p++;
        }
      }
      return false;
    }

    private static int parseUnicodeEscape(Ctx ctx) {
      if (ctx.p + 4 > ctx.n) {
        return -1;
      }
      int v = 0;
      for (int i = 0; i < 4; i++) {
        char c = ch(ctx, i);
        int h;
        if (c >= HEX_TABLE.length) {
          return -1;
        }
        h = HEX_TABLE[c];
        if (h < 0) {
          return -1;
        }
        v = (v << 4) | h;
      }
      ctx.p += 4;
      return v;
    }

    private static boolean parseNumber(Ctx ctx) {
      if (ch(ctx) == '-') {
        ctx.p++;
        if (ctx.p >= ctx.n) {
          return false;
        }
      }

      char c0 = ch(ctx);
      if (c0 == '0') {
        ctx.p++;
        if (ctx.p < ctx.n && ch(ctx) >= '0' && ch(ctx) <= '9') {
          return false;
        }
      } else if (c0 >= '1' && c0 <= '9') {
        ctx.p++;
        while (ctx.p < ctx.n && ch(ctx) >= '0' && ch(ctx) <= '9') {
          ctx.p++;
        }
      } else {
        return false;
      }

      if (ctx.p < ctx.n && ch(ctx) == '.') {
        ctx.p++;
        if (ctx.p >= ctx.n || ch(ctx) < '0' || ch(ctx) > '9') {
          return false;
        }
        ctx.p++;
        while (ctx.p < ctx.n && ch(ctx) >= '0' && ch(ctx) <= '9') {
          ctx.p++;
        }
      }

      if (ctx.p < ctx.n && (ch(ctx) == 'e' || ch(ctx) == 'E')) {
        ctx.p++;
        if (ctx.p >= ctx.n) {
          return false;
        }
        if (ch(ctx) == '+' || ch(ctx) == '-') {
          ctx.p++;
          if (ctx.p >= ctx.n) {
            return false;
          }
        }
        if (ch(ctx) < '0' || ch(ctx) > '9') {
          return false;
        }
        ctx.p++;
        while (ctx.p < ctx.n && ch(ctx) >= '0' && ch(ctx) <= '9') {
          ctx.p++;
        }
      }
      return true;
    }

    private static char[] acquireBuffer(int required) {
      char[] buf = TL_BUF.get();
      if (buf.length >= required) {
        return buf;
      }

      int newCap = buf.length;
      while (newCap < required) {
        newCap <<= 1;
      }
      char[] grown = new char[newCap];
      TL_BUF.set(grown);
      return grown;
    }

    private static char ch(Ctx ctx) {
      return ctx.buf[ctx.p];
    }

    private static char ch(Ctx ctx, int offset) {
      return ctx.buf[ctx.p + offset];
    }
  }
}