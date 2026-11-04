package io.github.meiyongai.toki.subtitle.model

/** Optional city representatives, not a claim of TikTok IP location or service availability. */
data class LocationPreset(val country: String, val name: String, val english: String, val zone: String, val latitude: Double, val longitude: Double)
object LocationPresets {
    val all = listOf(
        LocationPreset("US", "纽约州 · 纽约", "New York, New York", "America/New_York", 40.7128, -74.0060),
        LocationPreset("US", "加利福尼亚州 · 洛杉矶", "Los Angeles, California", "America/Los_Angeles", 34.0522, -118.2437),
        LocationPreset("US", "加利福尼亚州 · 旧金山", "San Francisco, California", "America/Los_Angeles", 37.7749, -122.4194),
        LocationPreset("US", "华盛顿州 · 西雅图", "Seattle, Washington", "America/Los_Angeles", 47.6062, -122.3321),
        LocationPreset("US", "内华达州 · 拉斯维加斯", "Las Vegas, Nevada", "America/Los_Angeles", 36.1699, -115.1398),
        LocationPreset("US", "德克萨斯州 · 达拉斯", "Dallas, Texas", "America/Chicago", 32.7767, -96.7970),
        LocationPreset("US", "德克萨斯州 · 休斯敦", "Houston, Texas", "America/Chicago", 29.7604, -95.3698),
        LocationPreset("US", "伊利诺伊州 · 芝加哥", "Chicago, Illinois", "America/Chicago", 41.8781, -87.6298),
        LocationPreset("US", "佛罗里达州 · 迈阿密", "Miami, Florida", "America/New_York", 25.7617, -80.1918),
        LocationPreset("US", "马萨诸塞州 · 波士顿", "Boston, Massachusetts", "America/New_York", 42.3601, -71.0589),
        LocationPreset("US", "华盛顿哥伦比亚特区", "Washington, DC", "America/New_York", 38.9072, -77.0369),
        LocationPreset("US", "科罗拉多州 · 丹佛", "Denver, Colorado", "America/Denver", 39.7392, -104.9903),
        LocationPreset("US", "亚利桑那州 · 凤凰城", "Phoenix, Arizona", "America/Phoenix", 33.4484, -112.0740),
        LocationPreset("US", "阿拉斯加州 · 安克雷奇", "Anchorage, Alaska", "America/Anchorage", 61.2181, -149.9003),
        LocationPreset("US", "夏威夷州 · 檀香山", "Honolulu, Hawaii", "Pacific/Honolulu", 21.3099, -157.8581),
        LocationPreset("CA", "安大略省 · 多伦多", "Toronto, Ontario", "America/Toronto", 43.6532, -79.3832),
        LocationPreset("CA", "不列颠哥伦比亚省 · 温哥华", "Vancouver, British Columbia", "America/Vancouver", 49.2827, -123.1207),
        LocationPreset("CA", "魁北克省 · 蒙特利尔", "Montreal, Quebec", "America/Toronto", 45.5017, -73.5673),
        LocationPreset("JP", "东京都 · 东京", "Tokyo", "Asia/Tokyo", 35.6762, 139.6503),
        LocationPreset("JP", "大阪府 · 大阪", "Osaka", "Asia/Tokyo", 34.6937, 135.5023),
        LocationPreset("GB", "英格兰 · 伦敦", "London, England", "Europe/London", 51.5074, -0.1278),
        LocationPreset("GB", "苏格兰 · 爱丁堡", "Edinburgh, Scotland", "Europe/London", 55.9533, -3.1883),
        LocationPreset("AU", "新南威尔士州 · 悉尼", "Sydney, New South Wales", "Australia/Sydney", -33.8688, 151.2093),
        LocationPreset("AU", "维多利亚州 · 墨尔本", "Melbourne, Victoria", "Australia/Melbourne", -37.8136, 144.9631),
        LocationPreset("AU", "西澳大利亚州 · 珀斯", "Perth, Western Australia", "Australia/Perth", -31.9523, 115.8613),
    )
    fun search(country: String, query: String): List<LocationPreset> = all.filter {
        it.country.equals(country, true) && (query.isBlank() || it.name.contains(query.trim(), true) || it.english.contains(query.trim(), true))
    }
    /** Use existing custom settings so old/rootless configuration channels do not reject a new schema key. */
    fun selection(item: LocationPreset): Map<String, Any> {
        require(item in all)
        val country = CountryCatalog.countries.single { it.isoCode == item.country }
        return CountryCatalog.selection(country) + mapOf(
            "custom_latitude" to item.latitude.toString(), "custom_longitude" to item.longitude.toString(),
            "custom_timezone" to item.zone, "gps_follow_region" to false, "timezone_follow_region" to false)
    }
}
