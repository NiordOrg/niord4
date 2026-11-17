/*
 * Copyright 2017 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.niord.core.aton;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.aton.LightCharacterModel.LightGroup;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses a light characteristic string into a LightModel and
 * formats the LightModel into a human readable text for a specific language.
 */
@SuppressWarnings("unused")
public class LightCharacterParser {

    public static String LIGHT_PHASES = "FFl|LFl|Fl|F|IVQ|VQ|IQ|IUQ|UQ|Q|Iso|Oc|Al|Mo|Gr";

    public static String LIGHT_COLORS = "W|R|G|B|Y|Am";

    public static Pattern LIGHT_CHARACTER_GROUPS = Pattern.compile(
            "^" +
            "(?<phase>(" + LIGHT_PHASES + "))" +
            "(\\((?<group>\\w+(\\+\\w+)*)\\))?\\s*" +
            "(?<colors>(" + LIGHT_COLORS + ")*)?"
    );

    public static Pattern LIGHT_FORMAT = Pattern.compile(
            "(?<color>" + LIGHT_COLORS + ")"
    );

    // A number may carry a decimal point ("2.5s"), which normalize() keeps
    private static final Pattern ELEVATION = Pattern.compile("(\\d+(?:\\.\\d+)?)m");

    private static final Pattern PERIOD = Pattern.compile("(\\d+(?:\\.\\d+)?)[sS]");

    private static final Pattern RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)M");

    /** No-access constructor **/
    private LightCharacterParser() {
    }

    /**
     * Returns an instance of the light character parser
     * @return an instance of the light character parser
     */
    public static LightCharacterParser getInstance() {
        return new LightCharacterParser();
    }

    /**
     * Normalizes the light character for easier parsing. A dot is removed as a separator unless it stands between
     * two digits, where it is a decimal point ("Fl.G.2.5s" becomes "FlG2.5s", a period of 2.5 seconds).
     * @param lightCharacter the light character to normalize
     * @return the normalized light character
     */
    public String normalize(String lightCharacter) {
        if (StringUtils.isBlank(lightCharacter)) {
            return lightCharacter;
        }
        return lightCharacter
                .replaceAll("(?<!\\d)\\.|\\.(?!\\d)", "")
                .replaceAll("\\s+"," ")
                .replace("Qk", "Q")
                .replace("Bu", "B")
                .replace("Bl", "B")
                .replace("Occ", "Oc")
                .replace("Alt", "Al")
                .replace("AlFl", "Al")
                .replace("Gp", "")
                .trim();
    }

    /**
     * Parses the light characteristic string into a light model
     * @param lightCharacter the light characteristic
     * @return the light model
     */
    public LightCharacterModel parse(String lightCharacter) throws Exception {
        if (StringUtils.isBlank(lightCharacter)) {
            throw new Exception("Blank light character");
        }

        // Normalize the light character
        String lc = normalize(lightCharacter);
        LightCharacterModel lightModel = new LightCharacterModel();

        while (true) {
            Matcher m = LIGHT_CHARACTER_GROUPS.matcher(lc);

            if (m.find()) {
                String phaseSpec = m.group("phase");
                String colorsSpec = m.group("colors");
                String groupSpec = m.group("group");

                lc = lc.substring(m.end()).trim();
                if (lc.startsWith("+")) {
                    lc = lc.substring(1).trim();
                }

                LightGroup lightGroup = new LightGroup();
                lightModel.getLightGroups().add(lightGroup);

                // Phase
                if (StringUtils.isNotBlank(phaseSpec)) {
                    lightGroup.setPhase(phaseSpec);
                }

                // Colors
                if (StringUtils.isNotBlank(colorsSpec)) {
                    Matcher cm = LIGHT_FORMAT.matcher(colorsSpec);
                    while (cm.find()) {
                        lightGroup.getColors().add(cm.group("color"));
                    }
                }

                // Group
                if (StringUtils.isNotBlank(groupSpec)) {
                    if ("Mo".equalsIgnoreCase(phaseSpec)) {
                        String morseCode = getTelephonyCode(groupSpec);
                        lightGroup.setMorseCode(morseCode);
                    } else {
                        lightGroup.setGrouped(true);
                        Arrays.stream(groupSpec.split("\\+"))
                                .map(g -> Integer.valueOf(g.trim()))
                                .forEach(g -> lightGroup.getGroupSpec().add(g));
                    }
                }
            } else {
                break;
            }
        }

        if (lightModel.getLightGroups().isEmpty()) {
            throw new Exception("Invalid light character format: " + lightCharacter);
        }

        // Extract elevation
        Matcher m = ELEVATION.matcher(lc);
        if (m.find()) {
            lightModel.setElevation(new BigDecimal(m.group(1)));
            lc = removeMatch(lc, m);
        }

        // Extract period
        m = PERIOD.matcher(lc);
        if (m.find()) {
            lightModel.setPeriod(new BigDecimal(m.group(1)));
            lc = removeMatch(lc, m);
        }

        // Extract range
        m = RANGE.matcher(lc);
        if (m.find()) {
            lightModel.setRange(new BigDecimal(m.group(1)));
            lc = removeMatch(lc, m);
        }

        if (StringUtils.isNotBlank(lc)) {
            throw new Exception("Invalid light character format: " + lightCharacter + ". Unknown part " + lc);
        }

        return lightModel;
    }

    /** Removes a regex match from the string **/
    private String removeMatch(String text, Matcher matcher) {
        String result = text.substring(0, matcher.start())
                + text.substring(matcher.end());
        return result.trim();
    }

    /**
     * Returns the telephony code for the given character
     * @param c the character. Valid intervals: a-z and 0-9
     * @return the telephony code or a blank string if invalid
     */
    public String getTelephonyCode(char c) {
        return switch (Character.toUpperCase(c)) {
            case 'A' -> "Alpha";
            case 'B' -> "Bravo";
            case 'C' -> "Charlie";
            case 'D' -> "Delta";
            case 'E' -> "Echo";
            case 'F' -> "Foxtrot";
            case 'G' -> "Golf";
            case 'H' -> "Hotel";
            case 'I' -> "India";
            case 'J' -> "Juliet";
            case 'K' -> "Kilo";
            case 'L' -> "Lima";
            case 'M' -> "Mike";
            case 'N' -> "November";
            case 'O' -> "Oscar";
            case 'P' -> "Papa";
            case 'Q' -> "Quebec";
            case 'R' -> "Romeo";
            case 'S' -> "Sierra";
            case 'T' -> "Tango";
            case 'U' -> "Uniform";
            case 'V' -> "Victor";
            case 'W' -> "Whiskey";
            case 'X' -> "Xray";
            case 'Y' -> "Yankee";
            case 'Z' -> "Zulu";
            case '1' -> "One";
            case '2' -> "Two";
            case '3' -> "Three";
            case '4' -> "Four";
            case '5' -> "Five";
            case '6' -> "Six";
            case '7' -> "Seven";
            case '8' -> "Eight";
            case '9' -> "Nine";
            case '0' -> "Zero";
            default -> "";
        };
    }

    /**
     * Returns the telephony code for the given string
     * @param text the string to return a telephony code for. Valid intervals: a-z and 0-9
     * @return the telephony code or a blank string if invalid
     */
    public String getTelephonyCode(String text) {
        return text.chars()
                .mapToObj(c -> (char)c)
                .map(this::getTelephonyCode)
                .collect(Collectors.joining("-"));
    }

    /**
     * Returns if the telephony code for the given string is valid
     * @param text the string to validate. Valid intervals: a-z and 0-9
     * @return if the telephony code for the given string is valid
     */
    public boolean telephonyCodeValid(String text) {
        return text.chars()
                .mapToObj(c -> (char)c)
                .allMatch(c -> StringUtils.isNotBlank(getTelephonyCode(c)));
    }

}
