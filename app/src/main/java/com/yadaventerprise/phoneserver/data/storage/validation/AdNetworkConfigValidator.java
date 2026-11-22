package com.yadaventerprise.phoneserver.data.storage.validation;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AdNetworkConfigValidator {
    private AdNetworkConfigValidator() {}

    public static ValidationResult validate(JSONObject root) {
        ValidationResult result = new ValidationResult("ad_network");
        if (root == null) {
            result.error("AD_CONFIG_REQUIRED", "ad_network", "Ad Network configuration is required.", "Provide ad_network object.");
            return result;
        }
        JSONArray users = root.optJSONArray("users");
        JSONArray sites = root.optJSONArray("sites");
        JSONArray placements = root.optJSONArray("placements");
        JSONArray campaigns = root.optJSONArray("campaigns");
        JSONArray creatives = root.optJSONArray("creatives");
        if (sites == null || placements == null || campaigns == null || creatives == null) {
            result.error("FIELD_REQUIRED", "ad_network", "sites, placements, campaigns, and creatives arrays are required.", "Include all required arrays.");
            return result;
        }
        int schemaVersion = root.optInt("schemaVersion", 1);
        if (schemaVersion != 1) {
            result.error("UNSUPPORTED_VERSION", "ad_network.schemaVersion", "Unsupported ad_network schemaVersion: " + schemaVersion, "Use schemaVersion 1.");
        }

        Set<Long> userIds = validateIds(users, "users", result, false);
        Set<Long> siteIds = validateIds(sites, "sites", result, true);
        Set<Long> placementIds = validateIds(placements, "placements", result, true);
        Set<Long> campaignIds = validateIds(campaigns, "campaigns", result, true);
        Set<Long> creativeIds = validateIds(creatives, "creatives", result, true);

        validateUsers(users, result);
        validateSites(sites, userIds, result);
        validatePlacements(placements, siteIds, result);
        validateCampaigns(campaigns, userIds, result);
        validateCreatives(creatives, campaignIds, result);
        if (placementIds.isEmpty() && placements.length() > 0) {
            result.warning("EMPTY_PLACEMENTS", "ad_network.placements", "No valid placement IDs found.", "Ensure placements have positive numeric ids.");
        }
        if (creativeIds.isEmpty() && creatives.length() > 0) {
            result.warning("EMPTY_CREATIVES", "ad_network.creatives", "No valid creative IDs found.", "Ensure creatives have positive numeric ids.");
        }
        return result;
    }

    private static void validateUsers(JSONArray users, ValidationResult result) {
        if (users == null) return;
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.optJSONObject(i);
            String ref = "ad_network.users[" + i + "]";
            if (u == null) continue;
            if (u.has("password") || u.has("api_key") || u.has("apiKey") || u.has("secret")) {
                result.error("SENSITIVE_FIELD", ref, "Sensitive plaintext credential fields are not allowed in ad_network import.", "Remove plaintext secrets; use pass_hash only when required.");
            }
            if (u.has("pass_hash") || u.has("passwordHash")) {
                result.warning("SENSITIVE_FIELD_HASH", ref, "Credential hash fields should be handled carefully.", "Keep hashed credentials protected and only import when necessary.");
            }
            String email = u.optString("email", "").trim();
            if (email.isEmpty()) result.error("FIELD_REQUIRED", ref + ".email", "User email is required.", "Set email.");
        }
    }

    private static void validateSites(JSONArray sites, Set<Long> userIds, ValidationResult result) {
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            String ref = "ad_network.sites[" + i + "]";
            if (s == null) continue;
            long ownerId = optLong(s, "owner_id", "ownerId");
            if (ownerId <= 0) result.error("FIELD_REQUIRED", ref + ".owner_id", "Site owner_id is required.", "Set owner_id to an existing user id.");
            else if (!userIds.isEmpty() && !userIds.contains(ownerId)) result.error("INVALID_REFERENCE", ref + ".owner_id", "Site references unknown user: " + ownerId, "Use existing user id.");
            if (s.optString("name", "").trim().isEmpty()) result.warning("FIELD_RECOMMENDED", ref + ".name", "Site name is missing.", "Set a descriptive site name.");
        }
    }

    private static void validatePlacements(JSONArray placements, Set<Long> siteIds, ValidationResult result) {
        for (int i = 0; i < placements.length(); i++) {
            JSONObject p = placements.optJSONObject(i);
            String ref = "ad_network.placements[" + i + "]";
            if (p == null) continue;
            long siteId = optLong(p, "site_id", "siteId");
            if (siteId <= 0) result.error("FIELD_REQUIRED", ref + ".site_id", "Placement site_id is required.", "Set site_id to an existing site id.");
            else if (!siteIds.contains(siteId)) result.error("INVALID_REFERENCE", ref + ".site_id", "Placement references unknown site: " + siteId, "Use existing site id.");
            if (p.optInt("width", 0) < 0 || p.optInt("height", 0) < 0) result.error("INVALID_VALUE", ref, "Placement dimensions cannot be negative.", "Use non-negative dimensions.");
        }
    }

    private static void validateCampaigns(JSONArray campaigns, Set<Long> userIds, ValidationResult result) {
        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject c = campaigns.optJSONObject(i);
            String ref = "ad_network.campaigns[" + i + "]";
            if (c == null) continue;
            long advertiserId = optLong(c, "advertiser_id", "advertiserId");
            if (advertiserId <= 0) result.error("FIELD_REQUIRED", ref + ".advertiser_id", "Campaign advertiser_id is required.", "Set advertiser_id to an existing user id.");
            else if (!userIds.isEmpty() && !userIds.contains(advertiserId)) result.error("INVALID_REFERENCE", ref + ".advertiser_id", "Campaign references unknown user: " + advertiserId, "Use existing user id.");
            double budget = c.has("total_budget_micros") ? c.optDouble("total_budget_micros", 0) : c.optDouble("budget", 0);
            if (budget < 0) result.error("INVALID_VALUE", ref + ".budget", "Budget cannot be negative.", "Use 0 or positive budget.");
        }
    }

    private static void validateCreatives(JSONArray creatives, Set<Long> campaignIds, ValidationResult result) {
        for (int i = 0; i < creatives.length(); i++) {
            JSONObject c = creatives.optJSONObject(i);
            String ref = "ad_network.creatives[" + i + "]";
            if (c == null) continue;
            long campaignId = optLong(c, "campaign_id", "campaignId");
            if (campaignId <= 0) result.error("FIELD_REQUIRED", ref + ".campaign_id", "Creative campaign_id is required.", "Set campaign_id to an existing campaign id.");
            else if (!campaignIds.contains(campaignId)) result.error("INVALID_REFERENCE", ref + ".campaign_id", "Creative references unknown campaign: " + campaignId, "Use existing campaign id.");
            long maxImpressions = optLong(c, "max_impressions", "maxImpressions");
            if (maxImpressions < 0) result.error("INVALID_VALUE", ref + ".maxImpressions", "maxImpressions cannot be negative.", "Use 0 or positive value.");
        }
    }

    private static Set<Long> validateIds(JSONArray arr, String name, ValidationResult result, boolean required) {
        Set<Long> ids = new HashSet<>();
        if (arr == null) {
            if (required) result.error("FIELD_REQUIRED", "ad_network." + name, name + " array is required.", "Include " + name + " array.");
            return ids;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String ref = "ad_network." + name + "[" + i + "]";
            if (o == null) { result.error("INVALID_RECORD", ref, "Record must be object.", "Provide object."); continue; }
            long id = o.optLong("id", -1);
            if (id <= 0) result.error("FIELD_REQUIRED", ref + ".id", "Positive numeric id is required.", "Provide unique positive numeric id.");
            else if (!ids.add(id)) result.error("DUPLICATE_ID", ref + ".id", "Duplicate id: " + id, "Use unique id.");
        }
        return ids;
    }

    private static long optLong(JSONObject o, String snake, String camel) {
        if (o.has(snake)) return o.optLong(snake, -1);
        if (o.has(camel)) return o.optLong(camel, -1);
        String lower = snake.toLowerCase(Locale.US);
        if (o.has(lower)) return o.optLong(lower, -1);
        return -1;
    }
}
