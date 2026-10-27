/*
 * Copyright (c) 2026, ShirOKhorshid
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.text.TextUtils;

import com.psiphon3.R;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A private CDN fronting config for the in-app Telegram tunnel.
 *
 * <p>It is a standalone copy of the CDN fronting code so that CDN changes made for
 * Telegram can never affect the main app's CDN fronting connection.
 */
public final class TelegramCdnConfig {

    private static final String[] CDN_FRONTING_TUNNEL_PROTOCOLS = {
            "FRONTED-MEEK-CDN-OSSH",
            "FRONTED-MEEK-CDN-HTTP-OSSH",
            "FRONTED-MEEK-CDN-QUIC-OSSH",
    };

    private TelegramCdnConfig() {
    }

    /**
     * Build the tunnel-core config JSON for the Telegram CDN tunnel.
     *
     * @return JSON string of config. null on error.
     */
    public static String build(Context context) {
        AppPreferences preferences = new AppPreferences(context);
        String customIpList = preferences.getString(
                context.getString(R.string.cdnFrontingCustomIpListPreference), "");
        String customSni = preferences.getString(
                context.getString(R.string.cdnFrontingCustomSniPreference), "");
        boolean beastMode = preferences.getBoolean(
                context.getString(R.string.beastModePreference), true);
        boolean disableTimeouts = preferences.getBoolean(
                context.getString(R.string.disableTimeoutsPreference), false);
        String egressRegion = preferences.getString(
                context.getString(R.string.egressRegionPreference),
                PsiphonConstants.REGION_CODE_ANY);

        JSONObject json = new JSONObject();

        try {
            json.put("ClientVersion", EmbeddedValues.CLIENT_VERSION);

            json.put("PropagationChannelId", EmbeddedValues.PROPAGATION_CHANNEL_ID);

            json.put("SponsorId", EmbeddedValues.SPONSOR_ID);

            json.put("RemoteServerListURLs", new JSONArray(EmbeddedValues.REMOTE_SERVER_LIST_URLS_JSON));

            json.put("ObfuscatedServerListRootURLs", new JSONArray(EmbeddedValues.OBFUSCATED_SERVER_LIST_ROOT_URLS_JSON));

            json.put("RemoteServerListSignaturePublicKey", EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY);

            json.put("ServerEntrySignaturePublicKey", EmbeddedValues.SERVER_ENTRY_SIGNATURE_PUBLIC_KEY);

            json.put("ExchangeObfuscationKey", EmbeddedValues.SERVER_ENTRY_EXCHANGE_OBFUSCATION_KEY);

            json.put("EmitDiagnosticNotices", true);

            json.put("EmitDiagnosticNetworkParameters", true);

            json.put("AdditionalParameters", EmbeddedValues.ADDITIONAL_PARAMETERS);

            File dataDirectory = new File(context.getFilesDir(), "telegram_cdn");
            if (!dataDirectory.exists()
                    && !dataDirectory.mkdirs()) {
                // Failed to create DB directory
                return null;
            }

            // On Android, these directories must be set to the app private storage area.
            json.put("DataRootDirectory", dataDirectory.getAbsolutePath());

            json.put("MigrateDataStoreDirectory", dataDirectory.getAbsolutePath());

            json.put("EgressRegion", egressRegion == null ? "" : egressRegion);

            if (disableTimeouts) {
                json.put("NetworkLatencyMultiplierLambda", 0.1);
            }

            putCdnFrontingConfig(json, customIpList, customSni);

            json.put("LimitTunnelProtocols", makeJsonArray(CDN_FRONTING_TUNNEL_PROTOCOLS));

            json.put("DisableTactics", true);

            if (beastMode) {
                json.put("AggressiveEstablishment", true);
            }

            json.put("DNSResolverAlternateServers", new JSONArray("[\"1.1.1.1\", \"1.0.0.1\", \"8.8.8.8\", \"8.8.4.4\"]"));

            json.put("EmitBytesTransferred", true);

            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONArray jsonArray(String... values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static JSONObject makeCdnFrontingOverride(
            String overrideID,
            JSONArray matchFrontingProviderIDRegexes,
            JSONArray matchDialAddressRegexes,
            String dialAddress,
            String sniServerName,
            JSONArray verifyServerNames,
            JSONArray alpnProtocols) throws JSONException {
        JSONObject override = new JSONObject();
        override.put("OverrideID", overrideID);
        if (matchFrontingProviderIDRegexes != null) {
            override.put("MatchFrontingProviderIDRegexes", matchFrontingProviderIDRegexes);
        }
        if (matchDialAddressRegexes != null) {
            override.put("MatchDialAddressRegexes", matchDialAddressRegexes);
        }
        override.put("DialAddresses", jsonArray(dialAddress));
        override.put("SNIServerName", sniServerName);
        override.put("VerifyServerNames", verifyServerNames);
        override.put("ALPNProtocols", alpnProtocols);
        override.put("TLSProfile", "Chrome-83");
        return override;
    }

    private static JSONArray makeEdgeVerifyServerNames(String ipAddress, String sniServerName) {
        JSONArray verifyServerNames = new JSONArray();
        Set<String> addedNames = new HashSet<>();

        putUniqueString(verifyServerNames, addedNames, sniServerName);
        putUniqueString(verifyServerNames, addedNames, ipAddress);
        putUniqueString(verifyServerNames, addedNames, "a248.e.akamai.net");
        putUniqueString(verifyServerNames, addedNames, "a.akamaized.net");
        putUniqueString(verifyServerNames, addedNames, "a.akamaized-staging.net");
        putUniqueString(verifyServerNames, addedNames, "a.akamaihd.net");
        putUniqueString(verifyServerNames, addedNames, "a.akamaihd-staging.net");
        putUniqueString(verifyServerNames, addedNames, "www.akamai.com");

        return verifyServerNames;
    }

    private static void putUniqueString(JSONArray array, Set<String> values, String value) {
        if (!TextUtils.isEmpty(value) && values.add(value)) {
            array.put(value);
        }
    }

    private static JSONObject makeEdgeCdnFrontingOverride(
            String overrideID,
            String ipAddress,
            String customSni) throws JSONException {

        String sniServerName = TextUtils.isEmpty(customSni) ? ipAddress : customSni;

        return makeCdnFrontingOverride(
                overrideID,
                null,
                jsonArray(".*"),
                ipAddress,
                sniServerName,
                makeEdgeVerifyServerNames(ipAddress, sniServerName),
                jsonArray("http/1.1"));
    }

    private static void putEdgeCdnFrontingOverride(
            JSONArray overrides,
            Set<String> dialAddresses,
            String overrideID,
            String ipAddress,
            String customSni) throws JSONException {
        if (dialAddresses.add(ipAddress)) {
            overrides.put(makeEdgeCdnFrontingOverride(overrideID, ipAddress, customSni));
        }
    }

    private static List<String> parseCdnFrontingCustomIpCandidates(String customIpList) {
        ArrayList<String> ipCandidates = new ArrayList<>();
        if (TextUtils.isEmpty(customIpList)) {
            return ipCandidates;
        }

        Set<String> seen = new HashSet<>();
        for (String entry : customIpList.split("[\\s,;]+")) {
            String ipCandidate = entry.trim();
            if (ipCandidate.isEmpty() ||
                    (!isValidIPv4Address(ipCandidate) && !isValidIPv4Cidr(ipCandidate))) {
                continue;
            }
            if (seen.add(ipCandidate)) {
                ipCandidates.add(ipCandidate);
            }
        }

        return ipCandidates;
    }

    private static List<String> parseCdnFrontingCustomSniList(String customSni) {
        ArrayList<String> sniServerNames = new ArrayList<>();
        if (TextUtils.isEmpty(customSni)) {
            return sniServerNames;
        }

        Set<String> seen = new HashSet<>();
        for (String entry : customSni.split("[\\s,;]+")) {
            String sni = normalizeHostname(entry);
            if (TextUtils.isEmpty(sni)) {
                continue;
            }
            if (seen.add(sni)) {
                sniServerNames.add(sni);
            }
        }

        return sniServerNames;
    }

    private static String normalizeCdnFrontingCustomSni(String customSni) {
        List<String> sniServerNames = parseCdnFrontingCustomSniList(customSni);
        if (sniServerNames.isEmpty()) {
            return "";
        }
        return sniServerNames.get(0);
    }

    private static boolean isValidIPv4Address(String ipAddress) {
        String[] parts = ipAddress.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }

        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }

        return true;
    }

    private static boolean isValidIPv4Cidr(String cidr) {
        if (TextUtils.isEmpty(cidr)) {
            return false;
        }
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2 || !isValidIPv4Address(parts[0])) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(parts[1]);
            return prefix >= 0 && prefix <= 32;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isValidHostname(String hostname) {
        if (TextUtils.isEmpty(hostname) || hostname.length() > 253 ||
                isValidIPv4Address(hostname)) {
            return false;
        }

        String normalizedHostname = hostname;
        if (normalizedHostname.endsWith(".")) {
            normalizedHostname = normalizedHostname.substring(0, normalizedHostname.length() - 1);
        }
        if (normalizedHostname.isEmpty()) {
            return false;
        }

        String[] labels = normalizedHostname.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 ||
                    label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char character = label.charAt(i);
                if (!Character.isLetterOrDigit(character) && character != '-') {
                    return false;
                }
            }
        }

        return true;
    }

    private static String normalizeHostname(String hostname) {
        if (TextUtils.isEmpty(hostname)) {
            return "";
        }
        String normalizedHostname = hostname.trim().toLowerCase(Locale.US);
        if (normalizedHostname.endsWith(".")) {
            normalizedHostname = normalizedHostname.substring(0, normalizedHostname.length() - 1);
        }
        if (!isValidHostname(normalizedHostname)) {
            return "";
        }
        return normalizedHostname;
    }

    private static JSONArray makeStringArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static JSONObject makeCdnFrontingScanSpec(
            String customIpList,
            String customSni) throws JSONException {
        List<String> ipCandidates = parseCdnFrontingCustomIpCandidates(customIpList);
        if (ipCandidates.isEmpty()) {
            return null;
        }

        JSONObject spec = new JSONObject();
        spec.put("IPCandidates", makeStringArray(ipCandidates));

        List<String> sniServerNames = parseCdnFrontingCustomSniList(customSni);
        if (!sniServerNames.isEmpty()) {
            spec.put("SNIServerNames", makeStringArray(sniServerNames));
        }

        return spec;
    }

    private static JSONArray makeCdnFrontingDialOverrides(String customSni) throws JSONException {
        JSONArray overrides = new JSONArray();
        Set<String> edgeDialAddresses = new HashSet<>();
        String edgeSni = normalizeCdnFrontingCustomSni(customSni);

        JSONArray fastlyVerifyServerNames = jsonArray(
                "www.python.org",
                "pypi.org",
                "fastly.com",
                "www.fastly.com",
                "developer.fastly.com",
                "githubassets.com",
                "github.com",
                "github.io",
                "githubusercontent.com");
        JSONArray fastlyALPNProtocols = jsonArray("h2", "http/1.1");

        overrides.put(makeCdnFrontingOverride(
                "fastly-provider",
                jsonArray("(?i)fastly"),
                null,
                "pypi.org",
                "pypi.org",
                fastlyVerifyServerNames,
                fastlyALPNProtocols));
        overrides.put(makeCdnFrontingOverride(
                "fastly-address",
                null,
                jsonArray("(?i)(fastly|pypi|python|github)"),
                "pypi.org",
                "pypi.org",
                fastlyVerifyServerNames,
                fastlyALPNProtocols));

        putEdgeCdnFrontingOverride(
                overrides, edgeDialAddresses, "edge-a-1", "23.215.0.206", edgeSni);
        putEdgeCdnFrontingOverride(
                overrides, edgeDialAddresses, "edge-a-2", "23.215.0.203", edgeSni);
        putEdgeCdnFrontingOverride(
                overrides, edgeDialAddresses, "edge-b-1", "23.212.250.91", edgeSni);
        putEdgeCdnFrontingOverride(
                overrides, edgeDialAddresses, "edge-b-2", "23.212.250.78", edgeSni);
        putEdgeCdnFrontingOverride(
                overrides, edgeDialAddresses, "edge-c-1", "23.12.147.13", edgeSni);
        putEdgeCdnFrontingOverride(
                overrides, edgeDialAddresses, "edge-c-2", "23.12.147.29", edgeSni);
        putEdgeCdnFrontingOverride(
                overrides, edgeDialAddresses, "edge-d-1", "23.73.207.8", edgeSni);
        putEdgeCdnFrontingOverride(
                overrides, edgeDialAddresses, "edge-d-2", "23.73.207.15", edgeSni);
        putEdgeCdnFrontingOverride(
                overrides, edgeDialAddresses, "edge-original", "92.123.102.43", edgeSni);

        return overrides;
    }

    private static void putCdnFrontingConfig(
            JSONObject json,
            String customIpList,
            String customSni) throws JSONException {
        json.put("FrontedMeekDialOverrides", makeCdnFrontingDialOverrides(customSni));
        json.put("FrontedMeekDialOverridesProbability", 1.0);
        json.put("FrontedMeekCDNScanUseBuiltInSpec", true);

        JSONObject scanSpec = makeCdnFrontingScanSpec(
                customIpList,
                customSni);
        if (scanSpec != null) {
            json.put("FrontedMeekCDNScanSpec", scanSpec);
        }
    }

    private static JSONArray makeJsonArray(String[] values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }
}
