/*******************************************************************************
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*******************************************************************************/

package org.testKitGen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class TestInfoParser {
	private Arguments arg;
	private ModesDictionary md;
	private Element testEle;
	private TestTarget tt;

	public TestInfoParser(Arguments arg, ModesDictionary md, Element testEle, TestTarget tt) {
		this.arg = arg;
		this.md = md;
		this.testEle = testEle;
		this.tt = tt;
	}

	public TestInfo parse() {
		TestInfo ti = new TestInfo(arg);

		String testCaseName = getImmediateChildContent(testEle, "testCaseName");
		if (testCaseName == null) {
			System.err.println("Error: missing testCaseName.");
			System.exit(1);
		}
		ti.setTestCaseName(testCaseName);

		// Do not generate make target if impl doesn't match the exported jdk_impl
		List<String> impls = new ArrayList<String>();
		getElements(impls, "impls", "impl", Constants.ALLIMPLS, ti.getTestCaseName());
		boolean isValidImpl = (impls.size() == 0) || impls.contains(arg.getImpl());
		if (!isValidImpl) return null;

		// Do not generate make target if vendor doesn't match the exported jdk_vendor
		List<String> vendors = new ArrayList<String>();
		getElements(vendors, "vendors", "vendor", null, ti.getTestCaseName());
		boolean isValidVendor = (vendors.size() == 0) || vendors.contains(arg.getVendor());
		if (!isValidVendor) return null;

		// Do not generate make target if version doesn't match the exported jdk_version
		List<String> versions = new ArrayList<String>();
		getElements(versions, "versions", "version", null, ti.getTestCaseName());
		boolean isValidVersion = versions.size() == 0;
		for (String version : versions) {
			isValidVersion = checkJavaVersion(version);
			if (isValidVersion) {
				break;
			}
		}
		if (!isValidVersion) return null;

		List<String> features = new ArrayList<String>();
		getElements(features, "features", "feature", null, ti.getTestCaseName());
		// defaults to applicable for all features
		if (features.size() == 0) {
			features.add("all:applicable");
		}
		for (String ft : features) {
			if (!ft.contains(":")) {
				ft += ":applicable";
			}
			String[] featElements = ft.split(":");
			ti.addFeature(featElements[0].toLowerCase(), featElements[1].toLowerCase());
		}
		Set<String> testFlags = new HashSet<>(arg.getTestFlag());
		for (Map.Entry<String,String> entry : ti.getFeatures().entrySet()) {
			String featureOpt = entry.getValue().toLowerCase();
			if (featureOpt.equals("required")) {
				if (!isFeatureInTestFlags(testFlags, entry.getKey())) {
					return null;
				}
			} else if (featureOpt.equals("nonapplicable")) {
				// Do not generate make target if the test is not applicable for one feature defined in TEST_FLAG
				if (isFeatureInTestFlags(testFlags, entry.getKey())) {
					return null;
				}
			} else if (featureOpt.equals("applicable") || featureOpt.equals("explicit")) {
				// Do nothing
			} else {
				System.err.println("Error: Please provide a valid feature parameter in test " + ti.getTestCaseName() + ". The valid string is <feature_name>:[required|applicable|nonapplicable|explicit].");
				System.exit(1);
			}
		}

		if (testFlags.contains("aot")) {
			for (Map.Entry<String,String> entry : ti.getFeatures().entrySet()) {
				if (doesFeatureMatchTestFlag("aot", entry.getKey())) {
					String featureOpt = entry.getValue().toLowerCase();
					if (featureOpt.equals("required") || featureOpt.equals("applicable")) {
						ti.setAotOptions("$(AOT_OPTIONS) ");
					} else if (featureOpt.equals("explicit")) {
						ti.setAotIterations(1);
					}
				}
			}
		}

		String rerun = getImmediateChildContent(testEle, "rerun");
		if (rerun != null) {
			if (rerun.equals("true")) {
				ti.setRerun(true);
			} else if (rerun.equals("false")) {
				ti.setRerun(false);
			} else {
				System.err.println("Error: Please provide a valid rerun parameter in test " + ti.getTestCaseName() + ". The valid string is [true|false].");
				System.exit(1);
			}
		} else {
			ti.setRerun(true);
		}

		String platform = getImmediateChildContent(testEle, "platform");
		if (platform != null) {
			ti.setPlatform(platform);
		}

		String preq = getImmediateChildContent(testEle, "platformRequirements");
		if ((preq != null) && (!preq.isEmpty())) {
			ti.addPlatformRequirements(preq);
		}

		getElements(ti.getPlatformRequirementsList(), "platformRequirementsList", "platformRequirements", null, ti.getTestCaseName());

		List<String> variations = new ArrayList<String>();
		getElements(variations, "variations", "variation", null, ti.getTestCaseName());
		List<Variation> listOfVars = new ArrayList<Variation>();
		for (int i = 0; i < variations.size(); i++) {
			String subTestName = ti.getTestCaseName() + "_" + i;
			Variation var = parseVariation(subTestName, variations.get(i), ti.getPlatform(), ti.getPlatformRequirementsList());
			listOfVars.add(var);
		}
		if (variations.size() == 0) {
			String subTestName = ti.getTestCaseName() + "_0";
			Variation var = parseVariation(subTestName, "NoOptions", ti.getPlatform(), ti.getPlatformRequirementsList());
			listOfVars.add(var);
		}
		ti.setVars(listOfVars);

		List<String> levels = new ArrayList<String>();
		getElements(levels, "levels", "level", Constants.ALLLEVELS, ti.getTestCaseName());
		// level defaults to "extended"
		if (levels.size() == 0) {
			levels.add("extended");
		}
		String levelStr = "";
		for (int i = 0; i < levels.size(); i++) {
			if (!levelStr.isEmpty()) {
				levelStr += ",";
			}
			levelStr = levelStr + "level." + levels.get(i);
			ti.setLevelStr(levelStr);
		}
		ti.setLevels(levels);

		List<String> groups = new ArrayList<String>();
		getElements(groups, "groups", "group", Constants.ALLGROUPS, ti.getTestCaseName());
		// group defaults to "extended"
		if (groups.size() == 0) {
			groups.add("functional");
		}
		ti.setGroups(groups);

		List<String> types = new ArrayList<String>();
		getElements(types, "types", "type", Constants.ALLTYPES, ti.getTestCaseName());
		// type defaults to "regular"
		if (types.size() == 0) {
			types.add("regular");
		}
		ti.setTypes(types);

		ti.setCommand(testEle.getElementsByTagName("command").item(0).getTextContent().trim());

		NodeList capabilitiesNodes = testEle.getElementsByTagName("capabilities");
		if (capabilitiesNodes.getLength() > 0) {
			String[] capabilityReqs_Arr = capabilitiesNodes.item(0).getTextContent().split(",");
			Map<String, String> capabilities = new HashMap<String, String>();
			for (String capabilityReq : capabilityReqs_Arr) {
				String[] colonSplit = capabilityReq.trim().split(":");
				capabilities.put(colonSplit[0], colonSplit[1]);
			}
			ti.setCapabilities(capabilities);
		}

		parseDisableInfo(ti);
		boolean filterResult = tt.filterTestInfo(ti);
		if (!filterResult) {
			ti = null;
		}
		return ti;
	}

	private boolean isFeatureInTestFlags(Set<String> testFlags, String feature) {
		for (String testFlag : testFlags) {
			if (doesFeatureMatchTestFlag(testFlag, feature)) {
				return true;
			}
		}
		return false;
	}

	private boolean doesFeatureMatchTestFlag(String testFlag, String feature) {
		if (feature.equals("all")) {
			return true;
		}
		if (!feature.startsWith("/") || !feature.endsWith("/")) {
			return testFlag.equalsIgnoreCase(feature);
		}
		Pattern pattern = Pattern.compile(feature.substring(1, feature.length() - 1));
		Matcher matcher = pattern.matcher(testFlag);
		if (matcher.matches()) {
			return true;
		}
		return false;
	}

	private boolean checkJavaVersion(String version) {
		if (version.equalsIgnoreCase(arg.getJdkVersion())) {
			return true;
		} else {
			try {
				Pattern pattern = Pattern.compile("^\\[(.*),(.*)\\]$");
				Matcher matcher = pattern.matcher(version);
				if (matcher.matches()) {
					String start = matcher.group(1).trim();
					String end = matcher.group(2).trim();
					int currentVersion = Integer.parseInt(arg.getJdkVersion());
					if (currentVersion >= Integer.parseInt(start)
						&& currentVersion <= Integer.parseInt(end)) {
						return true;
					}
				}

				pattern = Pattern.compile("^(.*)\\+$");
				matcher = pattern.matcher(version);
				if (matcher.matches()) {
					if (Integer.parseInt(matcher.group(1)) <= Integer.parseInt(arg.getJdkVersion())) {
						return true;
					}
				}
			} catch (NumberFormatException e) {
				System.out.println("Warning: jdk version is not an integer, couldn't parse it.");
			}
		}
		return false;
	}

	private void parseDisableInfo(TestInfo ti) {
		NodeList disabledNodes = null;
		NodeList disables = testEle.getElementsByTagName("disables");
		if (disables.getLength() > 0) {
			disabledNodes = ((Element) disables.item(0)).getElementsByTagName("disable");
		}
		if (disabledNodes == null || disabledNodes.getLength() == 0) return;
		for (int i = 0; i < disabledNodes.getLength(); i++) {
			Element disabled = (Element) disabledNodes.item(i);
			String comment = getDisabledEle(disabled, "comment", ti.getTestCaseName());
			if (comment == null) {
				System.err.println("Error: Please provide a comment inside disable element in test " + ti.getTestCaseName() + ".");
				System.exit(1);
			}

			String impl = getDisabledEle(disabled, "impl", ti.getTestCaseName());
			String vendor = getDisabledEle(disabled, "vendor", ti.getTestCaseName());
			String version = getDisabledEle(disabled, "version", ti.getTestCaseName());
			String platform = getDisabledEle(disabled, "platform", ti.getTestCaseName());
			String variation = getDisabledEle(disabled, "variation", ti.getTestCaseName());
			String testFlag = getDisabledEle(disabled, "testflag", ti.getTestCaseName());

			for (Variation var : ti.getVars()) {
				if (((impl == null) || arg.getImpl().equals(impl.toLowerCase()))
					&& ((vendor == null) || arg.getVendor().equals(vendor.toLowerCase()))
					&& ((version == null) || checkJavaVersion(version))
					&& ((platform == null) || checkPlat(platform))
					&& ((variation == null) || var.getVariation().equals(variation))
					&& ((testFlag == null) || arg.getTestFlag().contains(testFlag.toLowerCase()))) {
					var.addDisabledReasons(comment);
				}
			}
		}
	}

	private boolean checkPlat(String plat) {
		if (plat == null) return true;
		Pattern pattern = Pattern.compile(plat);
		Matcher matcher = pattern.matcher(arg.getPlat());
		return matcher.matches();
	}

	private String getDisabledEle(Element disabled, String ele, String test) {
		String rt = null;
		NodeList nodes = disabled.getElementsByTagName(ele);
		if (nodes != null) {
			if (nodes.getLength() == 1) {
				rt = nodes.item(0).getTextContent().trim();
			} else if (nodes.getLength() > 1) {
				System.err.println("Error: Multiple " + ele + " elements are not allowed in a single disable block (test " + test + "). The elements inside the disable element are in AND relationship. If you want to disable more than one " + ele + "s, please add more disable blocks. Or remove " + ele + " element from the block to disable all " + ele + "s.");
				System.exit(1);
			}
		}
		return rt;
	}

	private String getImmediateChildContent(Element ele, String name) {
		NodeList nl = ele.getElementsByTagName(name);
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			if (node.getParentNode().isSameNode(ele)) {
				return node.getTextContent().trim();
			}
		}
		return null;
	}

	private void getElements(List<String> list, String parentTag, String childTag, List<String> all, String testName) {
		NodeList parents = testEle.getElementsByTagName(parentTag);
		if (parents.getLength() > 0) {
			Element parentElement = (Element) parents.item(0);
			NodeList children = parentElement.getElementsByTagName(childTag);
			for (int i = 0; i < children.getLength(); i++) {
				Node child = children.item(i);
				String value = child.getTextContent().trim();
				if (value.isEmpty()) {
					System.out.println("Warning: The " + childTag + ": " + value + " for test " + testName
					+ " is empty, please remove it.");
					continue;
				}
				if ((all != null) && (!all.contains(value))) {
					System.err.println("Error: The " + childTag + ": " + value + " for test " + testName
							+ " is not valid, the valid " + childTag + " strings are " + joinStrList(all) + ".");
					System.exit(1);
				}
				list.add(child.getTextContent().trim());
			}
		}
	}

	private String joinStrList(List<String> list) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < list.size(); i++) {
			sb.append(list.get(i));
			if (i != list.size() - 1) {
				sb.append("\t");
			}
		}
		return sb.toString();
	}

	private Variation parseVariation(String subTestName, String variation, String platform, List<String> platformRequirementsList) {
		Variation var = new Variation(subTestName, variation);

		String jvmOptions = " " + variation + " ";

		jvmOptions = jvmOptions.replaceAll(" NoOptions ", "");

		Pattern pattern = Pattern.compile(".* Mode([^ ]*?) .*");
		Matcher matcher = pattern.matcher(jvmOptions);

		String mode = "";
		boolean isValid = true;
		if (matcher.matches()) {
			mode = matcher.group(1).trim();
			String clArgs = md.getClArgs(mode);
			isValid = md.isValidMode(mode);
			jvmOptions = jvmOptions.replace("Mode" + mode, clArgs);
		}
		jvmOptions = jvmOptions.trim();
		isValid &= checkPlat(platform);
		isValid &= checkPlatformReq(platformRequirementsList);

		var.setJvmOptions(jvmOptions);
		var.setMode(mode);
		var.setValid(isValid);
		return var;
	}

	private boolean checkPlatformReq(List<String> platformRequirementsList) {
		System.out.println("Checking platform requirements: " + platformRequirementsList);
		if (platformRequirementsList.isEmpty()) {
			return true;
		}
		for (String prs : platformRequirementsList) {
			System.out.println("Processing platform requirement set: " + prs);
			boolean isValid = true;
			for (String pr : prs.split("\\s*,\\s*")) {
				pr = pr.trim();
				boolean notPrefix = false;
				if (pr.startsWith("^")) {
					pr = pr.substring(1);
					notPrefix = true;
				}
				System.out.println("Modified platform requirement (pr): " + pr);
				String spec = arg.getSpec();
				String fullSpec = spec;
				System.out.println("FullSpec being compared: " + fullSpec);
				// Special case 32/31-bit specs which do not have 32 or 31 in the name (i.e.
				// aix_ppc)
				if (!spec.contains("-64")) {
					if (spec.contains("390")) {
						fullSpec = spec + "-31";
					} else {
						fullSpec = spec + "-32";
					}
				}

				boolean isMatch = matchPlat(fullSpec, pr);
				System.out.println("Matching result for " + pr + ": " + isMatch);
				if ((notPrefix && isMatch) || (!notPrefix && !isMatch)) {
					isValid = false;
					break;
				}
			}
			if (isValid) {
				return true;
			}
		}
		return false;
	}

	private boolean matchPlat1(String fullSpec, String pr) {
		String[] prSplitOnDot = pr.split("\\.");

		if (!fullSpec.contains(prSplitOnDot[1])) {
			return false;
		}
		System.out.println("microArch for checkplatformReq:" + Arrays.toString(prSplitOnDot) + " environmentMicroArch:" + arg.getMicroArch());

		if (prSplitOnDot[0].equals("arch") && (prSplitOnDot.length == 3)) {
			String microArchVersion = prSplitOnDot[2];
			String environmentMicroArch = arg.getMicroArch();
			System.out.println("microArchVersion from pr:" + microArchVersion + " environmentMicroArch:" + environmentMicroArch);
			if (!microArchVersion.startsWith("z")){
				return false;
			}
			if (microArchVersion.endsWith("+")) {
				int microArchVersionInt=0;
				int environmentMicroArchVerInt=0;
				try {
					microArchVersionInt = Integer.parseInt(microArchVersion.substring(1, microArchVersion.length() - 1));
					System.out.println("pr number as microArchVersion : " + microArchVersionInt);

					environmentMicroArchVerInt = Integer.parseInt(environmentMicroArch.substring(1, microArchVersion.length()-1));
					System.out.println("Extracted number as integer from microArch: " + environmentMicroArchVerInt);
				} catch (NumberFormatException e) {
					System.err.println(e.getMessage());
					return false;
				}
				if (microArchVersionInt > environmentMicroArchVerInt)
				{
					return false;
				}
			} else {
					if(!environmentMicroArch.equals(microArchVersion)){
						return false;
					}
			}
		} else if (prSplitOnDot[0].equals("os") && (prSplitOnDot.length == 4)) {
			String osName = prSplitOnDot[2];
			if (arg.getOsLabel().isEmpty()) {
				return false;
			}

			String[] osLabelArg = arg.getOsLabel().split("\\.");
			if (!osLabelArg[0].equals(osName)) {
				return false;
			}

			String osVersion = prSplitOnDot[3];
			if (osVersion.endsWith("+")) {
				int verInt = 0;
				try {
					verInt = Integer.parseInt(osVersion.substring(0, osVersion.length() - 1));
				} catch (NumberFormatException e) {
					System.out.println("Error: unrecognized platformRequirement: " + Arrays.toString(prSplitOnDot) + ". Only support integer OS version.");
					return false; // Adjusted to return false instead of System.exit(1);
				}

				int argVerInt = 0;
				try {
					argVerInt = Integer.parseInt(osLabelArg[1]);
				} catch (NumberFormatException e) {
					System.out.println("Error: unrecognized osLabel: " + arg.getOsLabel() + ". Only support integer OS version.");
					return false; // Adjusted to return false instead of System.exit(1);
				}
				return verInt <= argVerInt;
			} else {
				return osLabelArg[1].equals(osVersion);
			}
		}
		return true;
	}


	private int extractVersionNumber(String input, String context) throws NumberFormatException {
		Pattern pattern = Pattern.compile("\\d+");
		Matcher matcher = pattern.matcher(input);
		if (matcher.find()) {
			try {
				return Integer.parseInt(matcher.group());
			} catch (NumberFormatException e) {
				throw new NumberFormatException("Error parsing version number from " + context + ": " + input);
			}
		} else {
			throw new NumberFormatException("No numeric version found in " + context + ": " + input);
		}
	}

	private boolean matchPlat(String fullSpec, String pr) {
		String[] prSplitOnDot = pr.split("\\.");

		if (!fullSpec.contains(prSplitOnDot[1])) {
			return false;
		}
		if (prSplitOnDot[0].equals("arch") && (prSplitOnDot.length == 3)) {
			String requiredMicroArch = prSplitOnDot[2];
			String actualMicroArch = arg.getMicroArch();
			return compareVersion(requiredMicroArch, actualMicroArch);
		} else if (prSplitOnDot[0].equals("os") && (prSplitOnDot.length == 4)) {
			String osName = prSplitOnDot[2];
			String osVersion = prSplitOnDot[3];
			if (arg.getOsLabel().isEmpty()) {
				return false;
			}
			String[] osLabelArg = arg.getOsLabel().split("\\.");
			if (!osLabelArg[0].equals(osName)) {
				return false;
			}
			System.out.println("Debug - compareVersion: osVersion and sLabelArg[1] labels are:" + osVersion + " :" + osLabelArg[1] );
			return compareVersion(osVersion, osLabelArg[1]);
		}
		return true;
	}

	private boolean compareVersion(String requiredLabel, String actualLabel) {
		if (requiredLabel.isEmpty() || actualLabel.isEmpty()) {
			System.out.println("Debug - compareVersion: One or both labels are empty");
			return false;
		} else if (requiredLabel.equals(actualLabel)) {
			System.out.println("Debug - compareVersion:labels equals");
			return true;
		} else if (requiredLabel.endsWith("+")) {
			Pattern pattern = Pattern.compile("(\\D+)?(\\d+)");
			Matcher requiredLabelMatcher = pattern.matcher(requiredLabel);
			Matcher actualLabelMatcher = pattern.matcher(actualLabel);

		  if (requiredLabelMatcher.find() && actualLabelMatcher.find() && requiredLabelMatcher.groupCount() == 2 && actualLabelMatcher.groupCount() == 2) {
			System.out.println("Debug - compareVersion:labels equals:"+ requiredLabelMatcher.group(1)+ "required:  " + requiredLabelMatcher.groupCount());
			System.out.println("Debug - compareVersion:labels equals:"+ actualLabelMatcher.group(1)+ "required:  " + actualLabelMatcher.groupCount());

			if (requiredLabelMatcher.group(1).equals(actualLabelMatcher.group(1))) {
			  int requiredLabelNum = 0;
			  int actualLabelNum = 0;
			  try {
				requiredLabelNum = Integer.parseInt(requiredLabelMatcher.group(2));
				System.out.println("Debug - requiredLabelNum:"+requiredLabelNum);
				actualLabelNum = Integer.parseInt(actualLabelMatcher.group(2));
				System.out.println("Debug - actualLabelNum:"+actualLabelNum);
			  } catch (NumberFormatException e) {
				System.out.println("Error: unrecognized requiredLabel:" + requiredLabel + " or actualLabel:" + actualLabel);
				System.err.println(e.getMessage());
				System.exit(1);
			  }
			  if (actualLabelNum >= requiredLabelNum) {
				   return true;
				}
			}
		  }
		}
		return false;
	  }

	private boolean isVersionCompare(String prVersion, String environmentVersion) {
		Pattern pattern = Pattern.compile("\\d+");
    	Matcher prMatcher = pattern.matcher(prVersion);
    	Matcher envMatcher = pattern.matcher(environmentVersion);

    	int prVersionNumber = 0;
    	int environmentVersionNumber = 0;
		try {
			if (prMatcher.find()) {
				String prVersionNum = prVersion.endsWith("+") ? prMatcher.group() : prMatcher.group().substring(0, prMatcher.group().length());
				prVersionNumber = Integer.parseInt(prVersionNum);
				System.out.println("Debug: Extracted pr version number: " + prVersionNumber);
			}
			if (envMatcher.find()) {
				environmentVersionNumber = Integer.parseInt(envMatcher.group().substring(0, envMatcher.group().length()));
				System.out.println("Debug: Extracted detected environmentVersionNumber: " + environmentVersionNumber);
			}
		} catch (NumberFormatException e) {
			System.err.println("Version comparison error: " + e.getMessage());
			return false;
		}
		boolean comparisonResult = prVersionNumber > environmentVersionNumber;
    	System.out.println("Debug: Required version (" + prVersionNumber + ") > Environment version (" + environmentVersionNumber + ")? : " + comparisonResult);

		if (prVersionNumber > environmentVersionNumber) {
			return false;
		}
		else {
			return true;
		}

	}


}
