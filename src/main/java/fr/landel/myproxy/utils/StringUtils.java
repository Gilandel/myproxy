package fr.landel.myproxy.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StringUtils {

	public static final String EMPTY = "";

	public static final String NULL = "null";

	public static final String TRUE = "true";
	public static final String FALSE = "false";

	public static final String MARKER = "{}";
	public static final char MARKER_START_CHAR = '{';
	public static final char MARKER_STOP_CHAR = '}';

	private StringUtils() {
		throw new UnsupportedOperationException("utility class, not implemented");
	}

	public static String inject(final String text, final Object... args) {
		return inject(text, null, args);
	}

	public static <T> String inject(final String text, final Map<String, T> map, final Object... args) {
		if (text == null || text.indexOf(MARKER_START_CHAR) < 0) {
			return text;
		}

		final int argsLen = args == null ? 0 : args.length;
		final boolean mapEmpty = map == null || map.isEmpty();

		if (argsLen > 0 || !mapEmpty) {
			final int len = text.length();
			int index = 0;
			int previousClose = -1;
			int posOpen = 0;
			int posClose = -1;
			int argIndex;
			String key;
			Object value = null;
			final StringBuilder output = new StringBuilder();
			while ((posOpen = text.indexOf(MARKER_START_CHAR, posOpen)) > -1
					&& (posClose = text.indexOf(MARKER_STOP_CHAR, posOpen + 1)) > -1) {

				// manages \{
				if (posOpen > 0 && text.charAt(posOpen - 1) == '\\') {
					if (previousClose < posOpen - 2) {
						output.append(text.substring(previousClose + 1, posOpen - 1));
					}
					output.append(MARKER_START_CHAR);
					previousClose = posOpen++;

					// manages {}
				} else if (posOpen + 1 == posClose) {
					if ((previousClose == -1 && posOpen > 0) || (previousClose > 0 && previousClose < posOpen)) {
						output.append(text.substring(previousClose + 1, posOpen));
					}
					if (index < argsLen) {
						output.append(String.valueOf(args[index++]));
					}
					previousClose = posClose;
					posOpen = previousClose + 1;

					// manages {0} and {key}
				} else {
					key = text.substring(posOpen + 1, posClose);

					// manages {0}
					if (mapEmpty || (value = map.get(key)) == null) {
						argIndex = parsePositiveInt(key);
						if (argIndex > -1) {
							if ((previousClose == -1 && posOpen > 0)
									|| (previousClose > 0 && previousClose < posOpen)) {
								output.append(text.substring(previousClose + 1, posOpen));
							}
							if (argIndex < argsLen) {
								output.append(String.valueOf(args[argIndex]));
							}
							previousClose = posClose;
							posOpen = previousClose + 1;
						} else {
							++posOpen;
						}

						// manages {key}
					} else {
						if ((previousClose == -1 && posOpen > 0) || (previousClose > 0 && previousClose < posOpen)) {
							output.append(text.substring(previousClose + 1, posOpen));
						}
						output.append(String.valueOf(value));
						previousClose = posClose;
						posOpen = previousClose + 1;
					}
				}
			}
			if (previousClose < len - 1) {
				output.append(text.substring(previousClose + 1));
			}
			return output.toString();
		} else {
			return text.replace(MARKER, EMPTY);
		}
	}

	public static int parsePositiveInt(final String input) {
		final char[] array = input.toCharArray();
		final int len = array.length;
		if (len > 10) {
			return -1;
		}
		long value = 0;
		char c;
		int middle = len / 2;
		boolean odd = len % 2 != 0;
		for (int i = 0; (odd && i <= middle) || i < middle; ++i) {
			c = array[i];
			if (c > 48 && c < 58) {
				value += (c - 48) * Math.pow(10, len - i - 1);
			} else if (c != 48) {
				return -1;
			}
			if (middle > i) {
				c = array[len - i - 1];
				if (c > 48 && c < 58) {
					value += (c - 48) * Math.pow(10, i);
				} else if (c != 48) {
					return -1;
				}
			}
		}
		if (value <= (long) Integer.MAX_VALUE) {
			return (int) value;
		} else {
			return -1;
		}
	}

	public static boolean isEmpty(final CharSequence text) {
		return text == null || text.length() == 0;
	}

	public static boolean isNotEmpty(final CharSequence text) {
		return !isEmpty(text);
	}

	public static boolean isBlank(final CharSequence text) {
		return isEmpty(text) || text.toString().trim().length() == 0;
	}

	public static boolean isNotBlank(final CharSequence text) {
		return !isBlank(text);
	}

	public static boolean isEqual(final CharSequence text1, final CharSequence text2) {
		return (text1 != null && text1.equals(text2)) || (text1 == null && text2 == null);
	}

	public static boolean isNotEqual(final CharSequence text1, final CharSequence text2) {
		return !isEqual(text1, text2);
	}

	public static StringBuilder replace(final StringBuilder text, final String search, final String replacement) {
		if (isEmpty(text) || isEmpty(search) || replacement == null) {
			return text;
		}

		if (text.indexOf(search) < 0) {
			return text;
		}

		final int searchLength = search.length();
		final int replacementLength = replacement.length();

		int pos;
		int prevPos = 0;
		while ((pos = text.indexOf(search, prevPos)) > -1) {
			text.replace(pos, pos + searchLength, replacement);
			prevPos = pos + replacementLength;
		}

		return text;
	}

	public static String replace(final String text, final String search, final String replacement) {
		return replace(text, search, replacement, false);
	}

	public static String replace(final String text, final String search, final String replacement,
			final boolean caseInsensitive) {
		if (isEmpty(text) || isEmpty(search) || replacement == null) {
			return defaultIfEmpty(text, EMPTY);
		}

		final String textIn;
		final String searchIn;
		if (caseInsensitive) {
			textIn = text.toUpperCase();
			searchIn = search.toUpperCase();
		} else {
			textIn = text;
			searchIn = search;
		}

		if (textIn.indexOf(searchIn) < 0) {
			return text;
		}

		final StringBuilder result = new StringBuilder();
		final int length = search.length();

		int pos;
		int prevPos = 0;
		while ((pos = textIn.indexOf(searchIn, prevPos)) > -1) {
			result.append(text.substring(prevPos, pos)).append(replacement);
			prevPos = pos + length;
		}

		if (prevPos < text.length()) {
			result.append(text.substring(prevPos));
		}

		return result.toString();
	}

	public static String substring(final String text, final int start, final int end) {
		if (isEmpty(text) || start >= text.length()) {
			return defaultIfEmpty(text, EMPTY);
		}

		final int l = text.length();
		int st = start < 0 ? 0 : start;
		int en = end > l || end <= st ? l : end;

		return text.substring(st, en);
	}

	public static String defaultIfEmpty(final String text, final String defaultText) {
		return isNotEmpty(text) ? text : defaultText;
	}

	public static String defaultIfBlank(final String text, final String defaultText) {
		return isNotBlank(text) ? text : defaultText;
	}

	public static String trim(final String text) {
		return isNotEmpty(text) ? text.trim() : EMPTY;
	}

	public static String upperCase(final String text) {
		if (isEmpty(text)) {
			return defaultIfEmpty(text, EMPTY);
		} else {
			return text.toUpperCase();
		}
	}

	public static String join(final Iterable<String> iterable, final char character) {
		if (iterable == null) {
			return EMPTY;
		}

		final StringBuilder result = new StringBuilder();
		int count = 0;
		for (String element : iterable) {
			result.append(element).append(character);
			++count;
		}

		if (count > 0) {
			result.setLength(result.length() - 1);
		}

		return result.toString();
	}

	public static String[] split(final String text, final String separator) {
		if (isEmpty(text) || isEmpty(separator)) {
			return new String[0];
		}

		final List<String> result = new ArrayList<String>();

		final int length = separator.length();

		int pos;
		int prevPos = 0;

		while ((pos = text.indexOf(separator, prevPos)) > -1) {
			if (pos - prevPos > 0) {
				result.add(text.substring(prevPos, pos));
			} else {
				result.add(EMPTY);
			}
			prevPos = pos + length;
		}

		if (prevPos < text.length()) {
			result.add(text.substring(prevPos));
		} else {
			result.add(EMPTY);
		}

		return result.toArray(new String[result.size()]);
	}
}
