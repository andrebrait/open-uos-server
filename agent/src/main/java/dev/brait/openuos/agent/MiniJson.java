package dev.brait.openuos.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency RFC 8259 JSON parser and string escaper.
 * Keeps the premain agent free of Jackson/Gson classpath conflicts with Spring Boot.
 */
final class MiniJson {
    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        Object val = p.readValue();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw new IllegalArgumentException("Trailing JSON content at offset " + p.pos);
        }
        return val;
    }

    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw new IllegalArgumentException("Unexpected char '" + c + "' at offset " + pos);
            }
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            Object val = readValue();
            map.put(key, val);
            skipWs();
            if (peek('}')) {
                pos++;
                return map;
            }
            expect(',');
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWs();
            if (peek(']')) {
                pos++;
                return list;
            }
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= src.length()) {
                    throw new IllegalArgumentException("Unterminated escape");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        String hex = src.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> throw new IllegalArgumentException("Invalid escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Number readNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') {
            pos++;
        }
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            pos++;
        }
        boolean isFloat = false;
        if (pos < src.length() && src.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
        }
        String raw = src.substring(start, pos);
        return isFloat ? Double.parseDouble(raw) : Long.parseLong(raw);
    }

    private Object readLiteral(String lit, Object value) {
        if (!src.startsWith(lit, pos)) {
            throw new IllegalArgumentException("Expected " + lit + " at offset " + pos);
        }
        pos += lit.length();
        return value;
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at offset " + pos);
        }
        pos++;
    }
}
