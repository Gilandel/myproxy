package fr.landel.myproxy.utils;

import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MILLI_OF_SECOND;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static java.time.temporal.ChronoField.YEAR;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;

public class Logger {

	private static final DateTimeFormatter DATE_TIME_FORMATTER;
	static {
		DATE_TIME_FORMATTER = new DateTimeFormatterBuilder() //
				.appendValue(YEAR, 4) //
				.appendLiteral('/') //
				.appendValue(MONTH_OF_YEAR, 2) //
				.appendLiteral('/') //
				.appendValue(DAY_OF_MONTH, 2) //
				.appendLiteral(' ') //
				.appendValue(HOUR_OF_DAY, 2) //
				.appendLiteral(':') //
				.appendValue(MINUTE_OF_HOUR, 2) //
				.optionalStart() //
				.appendLiteral(':') //
				.appendValue(SECOND_OF_MINUTE, 2) //
				.appendLiteral('.') //
				.appendValue(MILLI_OF_SECOND, 3) //
				.toFormatter(Locale.getDefault());
	}

	private final String appender;

	public Logger(final Class<?> clazz) {
		this(clazz.getName());
	}

	public Logger(final String appender) {
		this.appender = appender;
	}

	public void info(final Object object) {
		log(null, System.out, String.valueOf(object));
	}

	public void info(final String text, final Object... args) {
		log(null, System.out, text, args);
	}

	public void info(final Throwable throwable, final String text, final Object... args) {
		log(throwable, System.out, text, args);
	}

	public void error(final Object object) {
		log(null, System.err, String.valueOf(object));
	}

	public void error(final String text, final Object... args) {
		log(null, System.err, text, args);
	}

	public void error(final Throwable throwable, final String text, final Object... args) {
		log(throwable, System.err, text, args);
	}

	private void log(final Throwable throwable, final PrintStream stream, final String text, final Object... args) {
		// args haven't to contain {}

		final StringBuilder builder = new StringBuilder() //
				.append(LocalDateTime.now().format(DATE_TIME_FORMATTER)) //
				.append(" - ") //
				.append(appender) //
				.append(" - ") //
				.append(args == null || args.length == 0 ? text : StringUtils.inject(text, args));

		stream.println(builder.toString());
		
		if (throwable != null) {
			throwable.printStackTrace(stream);
		}

	}
}
