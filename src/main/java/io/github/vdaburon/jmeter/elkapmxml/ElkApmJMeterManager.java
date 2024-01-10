package io.github.vdaburon.jmeter.elkapmxml;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class ElkApmJMeterManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(ElkApmJMeterManager.class);
	// fixed string to find in the jmeter script or in the extracted xml files
	private static final String PARAM_TC_NAME = "@@TC_NAME";
	private static final String COMMENT_BEGIN_ELK_APM = "@@ELK_APM_BEGIN";
	private static final String COMMENT_END_ELK_APM = "@@ELK_APM_END";
	private static final String COMMENT_APM_UDV = "@@ELK_APM_UDV";
	private static final String COMMENT_APM_SET_IGNORE = "@@ELK_APM_SET_IGNORE";

	// CLI OPTIONS
	public static final String K_JMETER_FILE_IN_OPT = "file_in";
	public static final String K_JMETER_FILE_OUT_OPT = "file_out";
	public static final String K_ACTION_OPT = "action";
	public static final String K_REGEX_OPT = "regex";
	public static final String K_EXTRACT_START_OPT = "extract_start";
	public static final String K_EXTRACT_END_OPT = "extract_end";
	public static final String K_EXTRACT_UDV_OPT = "extract_udv";

	public static final String ACTION_ADD = "ADD";
	public static final String ACTION_REMOVE = "REMOVE";

	// read the file from the filesystem
	private static final int READ_DIRECT_FILE = 1;
	// read file in the jar file
	private static final int READ_FROM_JAR = 2;

	// this files are in the jar
	public static final String EXTRACT_START_JSR223 = "extract_start_transaction_ignore_jsr223.xml";
	public static final String EXTRACT_END_JSR223 = "extract_end_transaction_ignore_jsr223.xml";
	public static final String EXTRACT_UDV_ELK = "extract_udv_elk_under_testplan.xml";

	public static void main(String[] args) {
		long startTime = System.currentTimeMillis();
		LOGGER.info("main begin");

		Options options = createOptions();
		Properties parseProperties = null;

		try {
			parseProperties = parseOption(options, args);
		} catch (ParseException ex) {
			helpUsage(options);
			LOGGER.info("main end (exit 1) ERROR");
			System.exit(1);
		}

		String sJMeterXmlFileScript = "";
		String sJMeterXmlFileScriptOutModif = "";
		String action = "";
		String regex = ".*";

		String sTmp = "";
		sTmp = (String) parseProperties.get(K_JMETER_FILE_IN_OPT);
		if (sTmp != null) {
			sJMeterXmlFileScript = sTmp;
		}

		sTmp = (String) parseProperties.get(K_JMETER_FILE_OUT_OPT);
		if (sTmp != null) {
			sJMeterXmlFileScriptOutModif = sTmp;
		}

		sTmp = (String) parseProperties.get(K_ACTION_OPT);
		if (sTmp != null) {
			action = sTmp;
		}

		sTmp = (String) parseProperties.get(K_REGEX_OPT);
		if (sTmp != null) {
			regex = sTmp;
		}

		if (ACTION_ADD.equalsIgnoreCase(action) || ACTION_REMOVE.equalsIgnoreCase(action)) {
			LOGGER.info("ACTION=" + action);
		} else {
			LOGGER.error("ACTION must be ADD or REMOVE, parameter = " + action);
			helpUsage(options);
			LOGGER.info("main end (exit 2) ERROR");
			System.exit(2);
		}

		LOGGER.info("file_in=" + sJMeterXmlFileScript);
		LOGGER.info("file_out=" + sJMeterXmlFileScriptOutModif);
		LOGGER.info("Regex Transaction Controller Label=" + regex);

		// XML extract to add jsr223 in the new file
		String sExtractAddStartJsr = EXTRACT_START_JSR223;
		String sExtractAddEndJsr = EXTRACT_END_JSR223;
		String sExtractUdvUnderTp = EXTRACT_UDV_ELK;

		sTmp = (String) parseProperties.get(K_EXTRACT_START_OPT);
		if (sTmp != null) {
			sExtractAddStartJsr = sTmp;
			LOGGER.info(K_EXTRACT_START_OPT + "=" + sExtractAddStartJsr);
		}

		sTmp = (String) parseProperties.get(K_EXTRACT_END_OPT);
		if (sTmp != null) {
			sExtractAddEndJsr = sTmp;
			LOGGER.info(K_EXTRACT_END_OPT + "=" + sExtractAddEndJsr);
		}

		sTmp = (String) parseProperties.get(K_EXTRACT_UDV_OPT);
		if (sTmp != null) {
			sExtractUdvUnderTp = sTmp;
			LOGGER.info(K_EXTRACT_UDV_OPT + "=" + sExtractUdvUnderTp);
		}

		modifyAddSamplerForElkApm(sJMeterXmlFileScript, sJMeterXmlFileScriptOutModif, action, regex, sExtractAddStartJsr, sExtractAddEndJsr, sExtractUdvUnderTp);

		long durationMs = System.currentTimeMillis() - startTime;
		LOGGER.info("Duration milli seconds=" + durationMs);
		LOGGER.info("main end (exit 0)");
		System.exit(0);
	}

	public static void modifyAddSamplerForElkApm(String jmeterXmlScript, String sJMeterXmlFileScriptModif, String action, String sRegexTcLabel, String sExtractAddStartJsr, String sExtractAddEndJsr, String sExtractUdvUnderTp) {
		LinkedList<String> lkFileJMeterOrig = readFileToLinkedList(jmeterXmlScript, READ_DIRECT_FILE);

		int readMode = READ_FROM_JAR;
		if (EXTRACT_START_JSR223.equals(sExtractAddStartJsr)) {
			readMode = READ_FROM_JAR;
		}
		else {
			readMode = READ_DIRECT_FILE;
		}
		LinkedList<String> lkStart = readFileToLinkedList(sExtractAddStartJsr, readMode);

		if (EXTRACT_END_JSR223.equals(sExtractAddEndJsr)) {
			readMode = READ_FROM_JAR;
		}
		else {
			readMode = READ_DIRECT_FILE;
		}
		LinkedList<String> lkEnd = readFileToLinkedList(sExtractAddEndJsr, readMode);

		if (EXTRACT_UDV_ELK.equals(sExtractUdvUnderTp)) {
			readMode = READ_FROM_JAR;
		}
		else {
			readMode = READ_DIRECT_FILE;
		}
		LinkedList<String> lkUdvUnderTp = readFileToLinkedList(sExtractUdvUnderTp, readMode);

		LinkedList<String> fileModified = null;
		if (ACTION_ADD.equals(action)) {
			fileModified = addSamplerForElkApm(lkFileJMeterOrig, sRegexTcLabel, lkStart, lkEnd, lkUdvUnderTp);
		}
		if (ACTION_REMOVE.equals(action)) {
			fileModified = removeSamplerForElkApm(lkFileJMeterOrig, sRegexTcLabel, lkStart, lkEnd, lkUdvUnderTp);
		}
		writeXml(fileModified, sJMeterXmlFileScriptModif);
	}


	private static LinkedList<String> addSamplerForElkApm(LinkedList<String> lkfileJMeterOrig, String regexTc, LinkedList<String> lkStart, LinkedList<String> lkEnd, LinkedList<String> lkUdvUnderTp) {
		LinkedList<String> lkReturn = new LinkedList<String>();

		Pattern patternStartEltTc = Pattern.compile(".*?<TransactionController guiclass=\"TransactionControllerGui\" testclass=\"TransactionController\" testname=\"(.*?)\" enabled=\"true\">");
		Pattern patternEndEltTc = Pattern.compile(".*?</TransactionController>");
		Pattern patternStartdEltHt = Pattern.compile(".*?<hashTree>");
		Pattern patternStartdEltHtEmpty = Pattern.compile(".*?<hashTree/>");
		Pattern patternEndEltTp = Pattern.compile(".*?</TestPlan>");

		Pattern patternRegexTc = Pattern.compile(regexTc); // e.g : SC\d+_P\d+_.* for SC01_P01 or SC12_P24

		String tcTestname = "";

		boolean bFirstTc = true;
		boolean bInTc = false;
		boolean bAlreadyAdd = false;
		boolean bInHashTree = false;
		String sEltEndHtToFind = "";
		Iterator<String> it = lkfileJMeterOrig.iterator();
		int numLine = 0;
		while (it.hasNext()) {
			String currentLine = it.next();
			numLine++;
			bAlreadyAdd = false;
			// <TransactionController guiclass="TransactionControllerGui" testclass="TransactionController" testname="SC01_PAGE02" enabled="true">
			Matcher matcherStart = patternStartEltTc.matcher(currentLine);
			boolean isStartEltTc = matcherStart.matches();
			if (isStartEltTc) {
				tcTestname = matcherStart.group(1);
				Matcher matcherRegexTc = patternRegexTc.matcher(tcTestname);
				boolean isRegexTc = matcherRegexTc.matches();
				if (isRegexTc) { // the testname == regexTc in parameter, yes we add the JSR223 for that TransactionController
					bInTc = true;
					if (bFirstTc) {
						bFirstTc = false;
						lkReturn.addAll(lkEnd); // add an end JSR223 before the first JSR223 because it could be a previous start with the startNextIteration on error
					}
					LinkedList<String> lkStartWithTcname = replaceInLinkedList(lkStart, PARAM_TC_NAME, tcTestname);
					lkReturn.addAll(lkStartWithTcname);
					lkReturn.add(currentLine);
					bAlreadyAdd = true;
				}
			}

			// </TransactionController>
			Matcher matcherEndEltTc = patternEndEltTc.matcher(currentLine);
			boolean isEndEltTc = matcherEndEltTc.matches();
			if (isEndEltTc && bInTc) {
				bInTc = false;
				lkReturn.add(currentLine);
				String nextLine = it.next(); // read <hashTree> or <hashTree/>
				lkReturn.add(nextLine);
				Matcher matcherStartdEltHtEmpty = patternStartdEltHtEmpty.matcher(nextLine);
				boolean isStartdEltHtEmpty = matcherStartdEltHtEmpty.matches();
				if (isStartdEltHtEmpty) { // <hashTree/> = empty
					lkReturn.addAll(lkEnd); // add the end jsr
				}

				Matcher matcherStartdEltHt = patternStartdEltHt.matcher(nextLine);
				boolean isStartdEltHt = matcherStartdEltHt.matches(); // <hashTree>
				if (isStartdEltHt) { // <hashTree> not empty, need find the elt </hashTree> at the good level
					bInHashTree = true;
					sEltEndHtToFind = matcherStartdEltHt.group(0).replace("<", "</"); // find </hashTree>
				}
				bAlreadyAdd = true;
			}

			// End </hashTree> after the TransactionController
			if (bInHashTree) {
				if (currentLine.equals(sEltEndHtToFind)) {
					bInHashTree = false;
					lkReturn.add(currentLine);
					lkReturn.addAll(lkEnd);
					bAlreadyAdd = true;
				}

			}

			// TestPlan
			Matcher matcherEndEltTp = patternEndEltTp.matcher(currentLine);
			boolean isEndEltTp = matcherEndEltTp.matches();
			if (isEndEltTp) {
				lkReturn.addAll(lkUdvUnderTp);
				bAlreadyAdd = true;
				String tempLine = it.next(); // read <hashTree> after </TestPlan>				
			}

			if (!bAlreadyAdd) {
				lkReturn.add(currentLine);
			}
		}
		return lkReturn;
	}

	private static LinkedList<String> removeSamplerForElkApm(LinkedList<String> lkfileJMeterOrig, String regexTc, LinkedList<String> lkStart, LinkedList<String> lkEnd, LinkedList<String> lkUdvUnderTp) {
		LinkedList<String> lkReturn = new LinkedList<String>();

		Pattern patternCommentStartEltTc = Pattern.compile(".*?<stringProp name=\"TestPlan.comments\">" + COMMENT_BEGIN_ELK_APM + "</stringProp>"); // JSR223 for begin transaction
		Pattern patternStartEltJsr223 = Pattern.compile(".*?<JSR223Sampler guiclass=\"TestBeanGUI\" testclass=\"JSR223Sampler\".*");

		Pattern patternCommentEndEltTc = Pattern.compile(".*?<stringProp name=\"TestPlan.comments\">" + COMMENT_END_ELK_APM + "</stringProp>"); // JSR223 for end transaction
		Pattern patternCommentEltArgument = Pattern.compile(".*?<stringProp name=\"TestPlan.comments\">" + COMMENT_APM_UDV + "</stringProp>"); // UDV with ELK_APM_UDV
		Pattern patternStartEltArguments = Pattern.compile(".*?<Arguments guiclass=\"ArgumentsPanel\" testclass=\"Arguments\".*");
		Pattern patternCommentEltPpIgnore = Pattern.compile(".*?<stringProp name=\"TestPlan.comments\">" + COMMENT_APM_SET_IGNORE + "</stringProp>"); // Post processor JSE223 with ignore
		Pattern patternStartEltHt = Pattern.compile(".*?<hashTree>");
		Pattern patternEndEltHt = Pattern.compile(".*?</hashTree>");


		boolean bAlreadyAdd = false;
		Iterator<String> it = lkfileJMeterOrig.iterator();
		int numLine = 0;
		while (it.hasNext()) {
			String currentLine = it.next();
			numLine++;
			bAlreadyAdd = false;
			Matcher matcherCommentStartEltTc = patternCommentStartEltTc.matcher(currentLine);
			boolean isCommentStartEltTc = matcherCommentStartEltTc.matches(); // 
			Matcher matcherCommentEndEltTc = patternCommentEndEltTc.matcher(currentLine);
			boolean isCommentEndEltTc = matcherCommentEndEltTc.matches(); // 

			if (isCommentStartEltTc || isCommentEndEltTc) { // @@ELK_APM_BEGIN or @@ELK_APM_END
				// remove lines in the lkReturn from comment  @@ELK_APM_BEGIN to <JSR223Sampler
				boolean bNeedRemove = true;
				while (bNeedRemove) {
					String lastLine = lkReturn.removeLast();

					Matcher matcherStartEltJsr223 = patternStartEltJsr223.matcher(lastLine);
					boolean isStartEltJsr223 = matcherStartEltJsr223.matches();
					if (isStartEltJsr223) {
						bNeedRemove = false;
					}

				}
				String tempLine = it.next(); // read </JSR223Sampler>
				bAlreadyAdd = true;
			}

			// <JSR223PostProcessor
			Matcher matcherCommentEltPpIgnore = patternCommentEltPpIgnore.matcher(currentLine);
			boolean isCommentEltPpIgnore = matcherCommentEltPpIgnore.matches();
			if (isCommentEltPpIgnore) {
				// remove lines in the lkReturn from comment @@ELK_APM_SET_IGNORE to <hashTree>
				boolean bNeedRemove = true;
				while (bNeedRemove) {
					String lastLine = lkReturn.removeLast();

					Matcher matcherStartEltHt = patternStartEltHt.matcher(lastLine);
					boolean isStartEltHt = matcherStartEltHt.matches();
					if (isStartEltHt) {
						bNeedRemove = false;
					}

				}
				boolean bReadUntilEndHt = true;
				while (bReadUntilEndHt) {
					String readLine = it.next(); // read until </hashTree>
					Matcher matcherEndEltHt = patternEndEltHt.matcher(readLine);
					boolean isEndEltHt = matcherEndEltHt.matches();
					if (isEndEltHt) {
						bReadUntilEndHt = false;
					}
				}
				bAlreadyAdd = true;
			}

			// @@ELK_APM_UDV
			Matcher matcherCommentEltArgument = patternCommentEltArgument.matcher(currentLine);
			boolean isCommentEltArgument = matcherCommentEltArgument.matches();
			if (isCommentEltArgument) {
				// remove lines in the lkReturn from comment  @@ELK_APM_UDV to <Arguments 
				boolean bNeedRemove = true;
				while (bNeedRemove) {
					String lastLine = lkReturn.removeLast();

					Matcher matcherStartEltArguments = patternStartEltArguments.matcher(lastLine);
					boolean isStartEltArguments = matcherStartEltArguments.matches();
					if (isStartEltArguments) {
						bNeedRemove = false;
					}

				}
				String tempLine = it.next(); // read </Arguments>
				String tempLine2 = it.next(); // read <hashTree/>

				bAlreadyAdd = true;
			}


			if (!bAlreadyAdd) {
				lkReturn.add(currentLine);
			}
		} // while

		return lkReturn;
	}

	private static LinkedList<String> replaceInLinkedList(LinkedList<String> lk, String toFind, String toReplace) {
		LinkedList<String> lkReturn = new LinkedList<String>();
		Iterator<String> it = lk.iterator();
		while (it.hasNext()) {
			String line = it.next();
			String newLIne = line.replace(toFind, toReplace);
			lkReturn.add(newLIne);
		}
		return lkReturn;
	}

	private static LinkedList<String> readFileToLinkedList(String jmeterXmlScript, int modeRead) {
		LinkedList<String> lkLines = new LinkedList<String>();
		BufferedReader reader = null;
		InputStreamReader isr = null;
		try {

			if (modeRead == READ_DIRECT_FILE) {
				FileInputStream fis = new FileInputStream(jmeterXmlScript);
				isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
				reader = new BufferedReader(isr);
			}

			if (modeRead == READ_FROM_JAR) {
				isr = new InputStreamReader(Thread.currentThread().getContextClassLoader().getResourceAsStream(jmeterXmlScript), StandardCharsets.UTF_8);
				reader = new BufferedReader(isr);
			}

			String str;
			while ((str = reader.readLine()) != null) {
				lkLines.add(str);
			}

		} catch (IOException e) {
			e.printStackTrace();
			LOGGER.error(e.toString());
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		return lkLines;
	}

	private static void writeXml(LinkedList<String> lkFileModified, String fileOut) {
		File file = new File(fileOut);
		BufferedWriter writer = null;
		try {
			FileOutputStream fos = new FileOutputStream(file);

			OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
			writer = new BufferedWriter(osw);
			Iterator<String> it = lkFileModified.iterator();
			while (it.hasNext()) {
				writer.append(it.next());
				writer.newLine();
			}
		} catch (IOException e) {
			e.printStackTrace();
			LOGGER.error(e.toString());
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

	}

	private static Options createOptions() {
		Options options = new Options();

		Option helpOpt = Option.builder("help").hasArg(false).desc("Help and show parameters").build();
		options.addOption(helpOpt);

		Option jmeterFileInOpt = Option.builder(K_JMETER_FILE_IN_OPT).argName(K_JMETER_FILE_IN_OPT).hasArg(true)
				.required(true).desc("JMeter file to read (e.g : script.jmx)").build();
		options.addOption(jmeterFileInOpt);

		Option jmeterFileOutOpt = Option.builder(K_JMETER_FILE_OUT_OPT).argName(K_JMETER_FILE_OUT_OPT).hasArg(true)
				.required(true).desc("JMeter file modified to write (e.g : script_add.jmx)").build();
		options.addOption(jmeterFileOutOpt);

		Option actionOpt = Option.builder(K_ACTION_OPT).argName(K_ACTION_OPT).hasArg(true)
				.required(true)
				.desc("action ADD or REMOVE, ADD : add groovy api call and REMOVE : remove groovy api call")
				.build();
		options.addOption(actionOpt);

		Option delimiterOpt = Option.builder(K_REGEX_OPT).argName(K_REGEX_OPT).hasArg(true)
				.required(false)
				.desc("regular expression matches Transaction Controller Label (default .*) (e.g : SC[0-9]+_. for SC01_P01_HOME or SC09_P12_LOGOUT)")
				.build();
		options.addOption(delimiterOpt);

		Option extractStartOpt = Option.builder(K_EXTRACT_START_OPT).argName(K_EXTRACT_START_OPT).hasArg(true)
				.required(false)
				.desc("optional, file contains groovy start call api (e.g : extract_start.xml), default read file in the jar")
				.build();
		options.addOption(extractStartOpt);

		Option extractEndOpt = Option.builder(K_EXTRACT_END_OPT).argName(K_EXTRACT_END_OPT).hasArg(true)
				.required(false)
				.desc("optional, file contains groovy end call api (e.g : extract_end.xml), default read file in the jar")
				.build();
		options.addOption(extractEndOpt);

		Option extractUdvOpt = Option.builder(K_EXTRACT_UDV_OPT).argName(K_EXTRACT_UDV_OPT).hasArg(true)
				.required(false)
				.desc("optional, file contains User Defined Variables (e.g : extract_udv.xml), default read file in the jar")
				.build();
		options.addOption(extractUdvOpt);

		return options;
	}

	private static Properties parseOption(Options optionsP, String args[])
			throws ParseException, MissingOptionException {
		Properties properties = new Properties();

		CommandLineParser parser = new DefaultParser();
		// parse the command line arguments
		CommandLine line = parser.parse(optionsP, args);

		if (line.hasOption("help")) {
			properties.setProperty("help", "help value");
			return properties;
		}

		if (line.hasOption(K_JMETER_FILE_IN_OPT)) {
			properties.setProperty(K_JMETER_FILE_IN_OPT, line.getOptionValue(K_JMETER_FILE_IN_OPT));
		}

		if (line.hasOption(K_JMETER_FILE_OUT_OPT)) {
			properties.setProperty(K_JMETER_FILE_OUT_OPT, line.getOptionValue(K_JMETER_FILE_OUT_OPT));
		}

		if (line.hasOption(K_ACTION_OPT)) {
			properties.setProperty(K_ACTION_OPT, line.getOptionValue(K_ACTION_OPT));
		}

		if (line.hasOption(K_REGEX_OPT)) {
			properties.setProperty(K_REGEX_OPT, line.getOptionValue(K_REGEX_OPT));
		}

		if (line.hasOption(K_EXTRACT_START_OPT)) {
			properties.setProperty(K_EXTRACT_START_OPT, line.getOptionValue(K_EXTRACT_START_OPT));
		}

		if (line.hasOption(K_EXTRACT_END_OPT)) {
			properties.setProperty(K_EXTRACT_END_OPT, line.getOptionValue(K_EXTRACT_END_OPT));
		}

		if (line.hasOption(K_EXTRACT_UDV_OPT)) {
			properties.setProperty(K_EXTRACT_UDV_OPT, line.getOptionValue(K_EXTRACT_UDV_OPT));
		}
		return properties;
	}

	private static void helpUsage(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		String footer = "E.g : java -jar jmeter-elk-apm-<version>-jar-with-dependencies.jar -" + K_JMETER_FILE_IN_OPT + " script1.jmx -"
				+ K_JMETER_FILE_OUT_OPT + " script1_add.jmx -" + K_ACTION_OPT + " ADD -"
				+ K_REGEX_OPT + " SC.*\n";
		footer+="E.g : java -jar jmeter-elk-apm-<version>-jar-with-dependencies.jar -" + K_JMETER_FILE_IN_OPT + " script1_add.jmx -"
				+ K_JMETER_FILE_OUT_OPT + " script1_remove.jmx -" + K_ACTION_OPT + " REMOVE -"
				+ K_REGEX_OPT + " .*";
		formatter.printHelp(120, ElkApmJMeterManager.class.getName(),
				ElkApmJMeterManager.class.getName(), options, footer, true);
	}
}



