package io.github.meiyongai.toki.subtitle.model

/**
 * 运营商与国家地区预设实体类。
 *
 * @property countryName 中文国家或地区名称。
 * @property englishName 英文国家或地区名称。
 * @property isoCode 两位 ISO 3166-1 标准国家或地区代号（大写）。
 * @property operatorCode 移动国家代码（MCC）与移动网络代码（MNC）组合。
 * @property operatorName 运营商官方显示名称。
 * @property defaultLanguage 地区推荐的 BCP-47 默认系统语言标签。
 * @property defaultTimeZone 地区推荐的 IANA 标准默认时区标识。
 * @property defaultLatitude 地区核心代表城市的纬度坐标。
 * @property defaultLongitude 地区核心代表城市的经度坐标。
 * @property flagEmoji 对应的地区旗帜表情字符。
 */
data class RegionPreset(
    val countryName: String,
    val englishName: String,
    val isoCode: String,
    val operatorCode: String,
    val operatorName: String,
    val defaultLanguage: String,
    val defaultTimeZone: String,
    val defaultLatitude: Double,
    val defaultLongitude: Double,
    val flagEmoji: String = RegionPresets.isoToFlagEmoji(isoCode)
)

/**
 * 全球主流国家与地区运营商参数预置数据库。
 *
 * 提供预设数据的枚举定义及多维度快速检索能力，收录全球主流核心国家及地区。
 */
object RegionPresets {

    /**
     * 将两位 ISO 3166-1 国家或地区代号转换为对应的 Unicode 旗帜表情字符。
     *
     * 依据 Unicode 规范，利用区域指示符符号（Regional Indicator Symbol，U+1F1E6 起始）
     * 将两位大写字母拼接映射为系统原生彩色旗帜表情。
     *
     * @param isoCode 两位国家或地区标准代号。
     * @return 对应地区的旗帜 Emoji 字符；若代码长度不为 2 或非纯字母则返回空字符串。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.model.RegionPreset`: 初始化预设实体的旗帜字符。
     * - `io.github.meiyongai.toki.subtitle.ui.screen.DashboardScreen`: 为当前生效配置显示旗帜字符。
     */
    fun isoToFlagEmoji(isoCode: String): String {
        val trimmed = isoCode.trim().uppercase(java.util.Locale.ROOT)
        if (trimmed.length != 2 || trimmed[0] !in 'A'..'Z' || trimmed[1] !in 'A'..'Z') {
            return ""
        }
        val firstChar = Character.toChars(0x1F1E6 + (trimmed[0] - 'A'))
        val secondChar = Character.toChars(0x1F1E6 + (trimmed[1] - 'A'))
        return String(firstChar) + String(secondChar)
    }

    /** 全球主流国家与地区预设全量列表，按英文字母序（A-Z）统一排序 */
    val ALL_PRESETS: List<RegionPreset> = listOf(
        // === 东亚 ===
        RegionPreset("日本", "Japan", "JP", "44010", "NTT DOCOMO", "ja-JP", "Asia/Tokyo", 35.6762, 139.6503),
        RegionPreset("韩国", "South Korea", "KR", "45005", "SKTelecom", "ko-KR", "Asia/Seoul", 37.5665, 126.9780),
        RegionPreset("中国台湾", "Taiwan", "TW", "46692", "Chunghwa Telecom", "zh-TW", "Asia/Taipei", 25.0330, 121.5654),
        RegionPreset("中国香港", "Hong Kong", "HK", "45412", "CMHK", "zh-HK", "Asia/Hong_Kong", 22.3193, 114.1694),
        RegionPreset("中国澳门", "Macau", "MO", "45501", "CTM", "zh-MO", "Asia/Macau", 22.1987, 113.5439),
        RegionPreset("蒙古", "Mongolia", "MN", "42899", "Mobicom", "mn-MN", "Asia/Ulaanbaatar", 47.8864, 106.9057),

        // === 北美洲 ===
        RegionPreset("美国", "United States", "US", "310410", "AT&T", "en-US", "America/New_York", 40.7128, -74.0060),
        RegionPreset("加拿大", "Canada", "CA", "302720", "Rogers", "en-CA", "America/Toronto", 43.6532, -79.3832),
        RegionPreset("墨西哥", "Mexico", "MX", "334020", "Telcel", "es-MX", "America/Mexico_City", 19.4326, -99.1332),

        // === 东南亚 ===
        RegionPreset("新加坡", "Singapore", "SG", "52501", "Singtel", "en-SG", "Asia/Singapore", 1.3521, 103.8198),
        RegionPreset("马来西亚", "Malaysia", "MY", "50212", "Maxis", "ms-MY", "Asia/Kuala_Lumpur", 3.1390, 101.6869),
        RegionPreset("泰国", "Thailand", "TH", "52001", "AIS", "th-TH", "Asia/Bangkok", 13.7563, 100.5018),
        RegionPreset("越南", "Vietnam", "VN", "45204", "Viettel", "vi-VN", "Asia/Ho_Chi_Minh", 10.8231, 106.6297),
        RegionPreset("印度尼西亚", "Indonesia", "ID", "51010", "Telkomsel", "id-ID", "Asia/Jakarta", -6.2088, 106.8456),
        RegionPreset("菲律宾", "Philippines", "PH", "51502", "Globe", "en-PH", "Asia/Manila", 14.5995, 120.9842),
        RegionPreset("柬埔寨", "Cambodia", "KH", "45601", "Smart", "km-KH", "Asia/Phnom_Penh", 11.5564, 104.9282),
        RegionPreset("老挝", "Laos", "LA", "45701", "LaoTelecom", "lo-LA", "Asia/Vientiane", 17.9757, 102.6331),
        RegionPreset("缅甸", "Myanmar", "MM", "41401", "MPT", "my-MM", "Asia/Yangon", 16.8661, 96.1951),

        // === 南亚与中亚 ===
        RegionPreset("印度", "India", "IN", "40445", "Airtel", "en-IN", "Asia/Kolkata", 28.6139, 77.2090),
        RegionPreset("巴基斯坦", "Pakistan", "PK", "41001", "Jazz", "ur-PK", "Asia/Karachi", 33.6844, 73.0479),
        RegionPreset("孟加拉国", "Bangladesh", "BD", "47001", "Grameenphone", "bn-BD", "Asia/Dhaka", 23.8103, 90.4125),
        RegionPreset("哈萨克斯坦", "Kazakhstan", "KZ", "40102", "Kcell", "kk-KZ", "Asia/Almaty", 43.2220, 76.8512),
        RegionPreset("乌兹别克斯坦", "Uzbekistan", "UZ", "43404", "Beeline UZ", "uz-UZ", "Asia/Tashkent", 41.2995, 69.2401),

        // === 欧洲 ===
        RegionPreset("白俄罗斯", "Belarus", "BY", "257002", "MTS", "be-BY", "Europe/Minsk", 53.9006, 27.5590),
        RegionPreset("英国", "United Kingdom", "GB", "23410", "O2", "en-GB", "Europe/London", 51.5074, -0.1278),
        RegionPreset("德国", "Germany", "DE", "26201", "Telekom", "de-DE", "Europe/Berlin", 52.5200, 13.4050),
        RegionPreset("法国", "France", "FR", "20801", "Orange", "fr-FR", "Europe/Paris", 48.8566, 2.3522),
        RegionPreset("荷兰", "Netherlands", "NL", "20408", "KPN", "nl-NL", "Europe/Amsterdam", 52.3676, 4.9041),
        RegionPreset("比利时", "Belgium", "BE", "20601", "Proximus", "nl-BE", "Europe/Brussels", 50.8503, 4.3517),
        RegionPreset("爱尔兰", "Ireland", "IE", "27201", "Vodafone IE", "en-IE", "Europe/Dublin", 53.3498, -6.2603),
        RegionPreset("瑞士", "Switzerland", "CH", "22801", "Swisscom", "de-CH", "Europe/Zurich", 47.3769, 8.5417),
        RegionPreset("奥地利", "Austria", "AT", "23201", "A1", "de-AT", "Europe/Vienna", 48.2082, 16.3738),
        RegionPreset("瑞典", "Sweden", "SE", "24001", "Telia", "sv-SE", "Europe/Stockholm", 59.3293, 18.0686),
        RegionPreset("挪威", "Norway", "NO", "24201", "Telenor", "nb-NO", "Europe/Oslo", 59.9139, 10.7522),
        RegionPreset("丹麦", "Denmark", "DK", "23801", "TDC", "da-DK", "Europe/Copenhagen", 55.6761, 12.5683),
        RegionPreset("芬兰", "Finland", "FI", "24405", "Elisa", "fi-FI", "Europe/Helsinki", 60.1699, 24.9384),
        RegionPreset("冰岛", "Iceland", "IS", "27401", "Siminn", "is-IS", "Atlantic/Reykjavik", 64.1466, -21.9426),

        // === 欧洲（南欧与东欧） ===
        RegionPreset("意大利", "Italy", "IT", "22201", "TIM", "it-IT", "Europe/Rome", 41.9028, 12.4964),
        RegionPreset("西班牙", "Spain", "ES", "21407", "Movistar", "es-ES", "Europe/Madrid", 40.4168, -3.7038),
        RegionPreset("葡萄牙", "Portugal", "PT", "26801", "Vodafone PT", "pt-PT", "Europe/Lisbon", 38.7223, -9.1393),
        RegionPreset("希腊", "Greece", "GR", "20201", "Cosmote", "el-GR", "Europe/Athens", 37.9838, 23.7275),
        RegionPreset("波兰", "Poland", "PL", "26001", "Plus", "pl-PL", "Europe/Warsaw", 52.2297, 21.0122),
        RegionPreset("捷克", "Czech Republic", "CZ", "23001", "T-Mobile CZ", "cs-CZ", "Europe/Prague", 50.0755, 14.4378),
        RegionPreset("匈牙利", "Hungary", "HU", "21630", "Telekom HU", "hu-HU", "Europe/Budapest", 47.4979, 19.0402),
        RegionPreset("罗马尼亚", "Romania", "RO", "22601", "Vodafone RO", "ro-RO", "Europe/Bucharest", 44.4268, 26.1025),
        RegionPreset("土耳其", "Turkey", "TR", "28601", "Turkcell", "tr-TR", "Europe/Istanbul", 41.0082, 28.9784),
        RegionPreset("俄罗斯", "Russia", "RU", "25001", "MTS", "ru-RU", "Europe/Moscow", 55.7558, 37.6173),
        RegionPreset("乌克兰", "Ukraine", "UA", "25501", "Vodafone UA", "uk-UA", "Europe/Kyiv", 50.4501, 30.5234),

        // === 大洋洲 ===
        RegionPreset("澳大利亚", "Australia", "AU", "50501", "Telstra", "en-AU", "Australia/Sydney", -33.8688, 151.2093),
        RegionPreset("新西兰", "New Zealand", "NZ", "53001", "Vodafone NZ", "en-NZ", "Pacific/Auckland", -36.8485, 174.7633),

        // === 中东 ===
        RegionPreset("阿联酋", "United Arab Emirates", "AE", "42402", "du", "ar-AE", "Asia/Dubai", 25.2048, 55.2708),
        RegionPreset("沙特阿拉伯", "Saudi Arabia", "SA", "42001", "STC", "ar-SA", "Asia/Riyadh", 24.7136, 46.6753),
        RegionPreset("卡塔尔", "Qatar", "QA", "42701", "Ooredoo", "ar-QA", "Asia/Qatar", 25.2854, 51.5310),
        RegionPreset("科威特", "Kuwait", "KW", "41902", "Zain KW", "ar-KW", "Asia/Kuwait", 29.3759, 47.9774),
        RegionPreset("以色列", "Israel", "IL", "42501", "Partner", "he-IL", "Asia/Jerusalem", 32.0853, 34.7818),

        // === 南美洲 ===
        RegionPreset("巴西", "Brazil", "BR", "72405", "Claro BR", "pt-BR", "America/Sao_Paulo", -23.5505, -46.6333),
        RegionPreset("阿根廷", "Argentina", "AR", "722310", "Claro AR", "es-AR", "America/Argentina/Buenos_Aires", -34.6037, -58.3816),
        RegionPreset("智利", "Chile", "CL", "73001", "Entel", "es-CL", "America/Santiago", -33.4489, -70.6693),
        RegionPreset("哥伦比亚", "Colombia", "CO", "732101", "Claro CO", "es-CO", "America/Bogota", 4.7110, -74.0721),
        RegionPreset("秘鲁", "Peru", "PE", "71610", "Claro PE", "es-PE", "America/Lima", -12.0464, -77.0428),

        // === 非洲 ===
        RegionPreset("南非", "South Africa", "ZA", "65501", "Vodacom", "en-ZA", "Africa/Johannesburg", -26.2041, 28.0473),
        RegionPreset("埃及", "Egypt", "EG", "60202", "Vodafone EG", "ar-EG", "Africa/Cairo", 30.0444, 31.2357),
        RegionPreset("尼日利亚", "Nigeria", "NG", "62120", "MTN NG", "en-NG", "Africa/Lagos", 6.5244, 3.3792),
        RegionPreset("肯尼亚", "Kenya", "KE", "63902", "Safaricom", "en-KE", "Africa/Nairobi", -1.2921, 36.8219),
        RegionPreset("摩洛哥", "Morocco", "MA", "60401", "Maroc Telecom", "ar-MA", "Africa/Casablanca", 33.5731, -7.5898),

        // === 补充国家与属地环境预设；不代表 TikTok 官方开放范围，也不包含州级配置 ===
        RegionPreset("波多黎各", "Puerto Rico", "PR", "330110", "T-Mobile PR", "es-PR", "America/Puerto_Rico", 18.4655, -66.1057),
        RegionPreset("关岛", "Guam", "GU", "310470", "Docomo Pacific", "en-GU", "Pacific/Guam", 13.4443, 144.7937),
        RegionPreset("美属维尔京群岛", "US Virgin Islands", "VI", "332110", "Viya", "en-VI", "America/St_Thomas", 18.3358, -64.8963),
        RegionPreset("北马里亚纳群岛", "Northern Mariana Islands", "MP", "310280", "IT&E", "en-MP", "Pacific/Saipan", 15.0979, 145.6739),
        RegionPreset("美属萨摩亚", "American Samoa", "AS", "54401", "BlueSky", "en-AS", "Pacific/Pago_Pago", -14.2710, -170.1322),
        RegionPreset("法属波利尼西亚", "French Polynesia", "PF", "54720", "Vini", "fr-PF", "Pacific/Tahiti", -17.5516, -149.5585),
        RegionPreset("新喀里多尼亚", "New Caledonia", "NC", "54601", "OPT NC", "fr-NC", "Pacific/Noumea", -22.2558, 166.4503),
        RegionPreset("库拉索", "Curaçao", "CW", "36291", "Digicel CW", "nl-CW", "America/Curacao", 12.1696, -68.9900),
        RegionPreset("阿鲁巴", "Aruba", "AW", "36301", "Setar", "nl-AW", "America/Aruba", 12.5211, -69.9683),
        RegionPreset("开曼群岛", "Cayman Islands", "KY", "34601", "C3", "en-KY", "America/Cayman", 19.3133, -81.2546),
        RegionPreset("百慕大", "Bermuda", "BM", "35001", "One Communications", "en-BM", "Atlantic/Bermuda", 32.3078, -64.7505),
        RegionPreset("格陵兰", "Greenland", "GL", "29001", "TELE Greenland", "kl-GL", "America/Godthab", 64.1814, -51.6941),
        RegionPreset("中国", "China", "CN", "46000", "China Mobile", "zh-CN", "Asia/Shanghai", 39.9042, 116.4074),
        RegionPreset("朝鲜", "North Korea", "KP", "46701", "Koryolink", "ko-KP", "Asia/Pyongyang", 39.0392, 125.7625),
        RegionPreset("尼泊尔", "Nepal", "NP", "42901", "Nepal Telecom", "ne-NP", "Asia/Kathmandu", 27.7172, 85.3240),
        RegionPreset("斯里兰卡", "Sri Lanka", "LK", "41302", "Dialog", "si-LK", "Asia/Colombo", 6.9271, 79.8612),
        RegionPreset("不丹", "Bhutan", "BT", "40211", "B-Mobile", "dz-BT", "Asia/Thimphu", 27.4712, 89.6339),
        RegionPreset("马尔代夫", "Maldives", "MV", "47201", "Dhiraagu", "dv-MV", "Indian/Maldives", 4.1755, 73.5093),
        RegionPreset("文莱", "Brunei", "BN", "52811", "DST", "ms-BN", "Asia/Brunei", 4.5353, 114.7277),
        RegionPreset("东帝汶", "Timor-Leste", "TL", "51401", "Telkomcel", "pt-TL", "Asia/Dili", -8.5569, 125.5603),
        RegionPreset("伊朗", "Iran", "IR", "43211", "Hamrahe Aval", "fa-IR", "Asia/Tehran", 35.6892, 51.3890),
        RegionPreset("伊拉克", "Iraq", "IQ", "41830", "Zain IQ", "ar-IQ", "Asia/Baghdad", 33.3152, 44.3661),
        RegionPreset("叙利亚", "Syria", "SY", "41701", "MTN", "ar-SY", "Asia/Damascus", 33.5138, 36.2765),
        RegionPreset("约旦", "Jordan", "JO", "41601", "Zain JO", "ar-JO", "Asia/Amman", 31.9539, 35.9106),
        RegionPreset("黎巴嫩", "Lebanon", "LB", "41501", "Alfa", "ar-LB", "Asia/Beirut", 33.8938, 35.5018),
        RegionPreset("巴勒斯坦", "Palestine", "PS", "42501", "Jawwal", "ar-PS", "Asia/Hebron", 31.9522, 35.2332),
        RegionPreset("也门", "Yemen", "YE", "42101", "MTN Yemen", "ar-YE", "Asia/Aden", 15.3694, 44.1910),
        RegionPreset("阿曼", "Oman", "OM", "42201", "Omantel", "ar-OM", "Asia/Muscat", 23.5880, 58.3829),
        RegionPreset("巴林", "Bahrain", "BH", "42601", "Batelco", "ar-BH", "Asia/Bahrain", 26.0667, 50.5577),
        RegionPreset("格鲁吉亚", "Georgia", "GE", "28201", "MagtiCom", "ka-GE", "Asia/Tbilisi", 41.7151, 44.8271),
        RegionPreset("亚美尼亚", "Armenia", "AM", "28301", "Beeline AM", "hy-AM", "Asia/Yerevan", 40.1792, 44.4991),
        RegionPreset("阿塞拜疆", "Azerbaijan", "AZ", "40001", "Azercell", "az-AZ", "Asia/Baku", 40.4093, 49.8671),
        RegionPreset("摩尔多瓦", "Moldova", "MD", "25901", "Orange MD", "ro-MD", "Europe/Chisinau", 47.0105, 28.8638),
        RegionPreset("卢森堡", "Luxembourg", "LU", "27001", "POST", "lb-LU", "Europe/Luxembourg", 49.6116, 6.1319),
        RegionPreset("爱沙尼亚", "Estonia", "EE", "24801", "Telia Eesti", "et-EE", "Europe/Tallinn", 59.4370, 24.7536),
        RegionPreset("拉脱维亚", "Latvia", "LV", "24701", "LMT", "lv-LV", "Europe/Riga", 56.9496, 24.1052),
        RegionPreset("立陶宛", "Lithuania", "LT", "24601", "Telia LT", "lt-LT", "Europe/Vilnius", 54.6872, 25.2797),
        RegionPreset("斯洛文尼亚", "Slovenia", "SI", "29301", "Mobitel", "sl-SI", "Europe/Ljubljana", 46.0569, 14.5058),
        RegionPreset("克罗地亚", "Croatia", "HR", "21901", "Hrvatski Telekom", "hr-HR", "Europe/Zagreb", 45.8150, 15.9819),
        RegionPreset("塞尔维亚", "Serbia", "RS", "22001", "MTS", "sr-RS", "Europe/Belgrade", 44.7866, 20.4489),
        RegionPreset("波黑", "Bosnia and Herzegovina", "BA", "21801", "BH Telecom", "bs-BA", "Europe/Sarajevo", 43.8563, 18.4131),
        RegionPreset("北马其顿", "North Macedonia", "MK", "29401", "Makedonski Telekom", "mk-MK", "Europe/Skopje", 41.9973, 21.4280),
        RegionPreset("阿尔巴尼亚", "Albania", "AL", "27601", "Vodafone AL", "sq-AL", "Europe/Tirane", 41.3275, 19.8187),
        RegionPreset("马耳他", "Malta", "MT", "27801", "Epic", "mt-MT", "Europe/Malta", 35.8989, 14.5146),
        RegionPreset("塞浦路斯", "Cyprus", "CY", "28001", "Cyta", "el-CY", "Asia/Nicosia", 35.1856, 33.3823),
        RegionPreset("保加利亚", "Bulgaria", "BG", "28401", "A1 BG", "bg-BG", "Europe/Sofia", 42.6977, 23.3219),
        RegionPreset("斯洛伐克", "Slovakia", "SK", "23101", "Orange SK", "sk-SK", "Europe/Bratislava", 48.1486, 17.1077),
        RegionPreset("黑山", "Montenegro", "ME", "29701", "Crnogorski Telekom", "sr-ME", "Europe/Podgorica", 42.4304, 19.2594),
        RegionPreset("委内瑞拉", "Venezuela", "VE", "73401", "Movilnet", "es-VE", "America/Caracas", 10.4806, -66.9036),
        RegionPreset("厄瓜多尔", "Ecuador", "EC", "74001", "Claro EC", "es-EC", "America/Guayaquil", -2.1763, -79.8861),
        RegionPreset("玻利维亚", "Bolivia", "BO", "73601", "Entel", "es-BO", "America/La_Paz", -16.4897, -68.1193),
        RegionPreset("巴拉圭", "Paraguay", "PY", "74401", "Tigo", "es-PY", "America/Asuncion", -25.2637, -57.5759),
        RegionPreset("乌拉圭", "Uruguay", "UY", "74801", "Antel", "es-UY", "America/Montevideo", -34.9011, -56.1645),
        RegionPreset("圭亚那", "Guyana", "GY", "73801", "GTT", "en-GY", "America/Guyana", 6.8013, -58.1551),
        RegionPreset("苏里南", "Suriname", "SR", "74601", "Telesur", "nl-SR", "America/Paramaribo", 5.8520, -55.2038),
        RegionPreset("危地马拉", "Guatemala", "GT", "70401", "Tigo GT", "es-GT", "America/Guatemala", 14.6349, -90.5069),
        RegionPreset("洪都拉斯", "Honduras", "HN", "70801", "Tigo HN", "es-HN", "America/Tegucigalpa", 14.0723, -87.1921),
        RegionPreset("萨尔瓦多", "El Salvador", "SV", "70601", "Claro SV", "es-SV", "America/El_Salvador", 13.6929, -89.2182),
        RegionPreset("尼加拉瓜", "Nicaragua", "NI", "71001", "Claro NI", "es-NI", "America/Managua", 12.1150, -86.2362),
        RegionPreset("哥斯达黎加", "Costa Rica", "CR", "71201", "Kölbi", "es-CR", "America/Costa_Rica", 9.7489, -83.7534),
        RegionPreset("巴拿马", "Panama", "PA", "71401", "Cable & Wireless", "es-PA", "America/Panama", 8.9824, -79.5199),
        RegionPreset("伯利兹", "Belize", "BZ", "70201", "DigiCell", "en-BZ", "America/Belize", 17.1899, -88.4976),
        RegionPreset("古巴", "Cuba", "CU", "36801", "ETECSA", "es-CU", "America/Havana", 23.1136, -82.3666),
        RegionPreset("多米尼加共和国", "Dominican Republic", "DO", "37001", "Altice", "es-DO", "America/Santo_Domingo", 18.4861, -69.9312),
        RegionPreset("牙买加", "Jamaica", "JM", "33801", "Digicel", "en-JM", "America/Jamaica", 18.1096, -77.2975),
        RegionPreset("海地", "Haiti", "HT", "37201", "Digicel HT", "ht-HT", "America/Port-au-Prince", 18.5944, -72.3074),
        RegionPreset("特立尼达和多巴哥", "Trinidad and Tobago", "TT", "37401", "bmobile", "en-TT", "America/Port_of_Spain", 10.6918, -61.2225),
        RegionPreset("巴哈马", "Bahamas", "BS", "36401", "BTC", "en-BS", "America/Nassau", 25.0343, -77.3963),
        RegionPreset("巴巴多斯", "Barbados", "BB", "34201", "FLOW", "en-BB", "America/Barbados", 13.1939, -59.5432),
        RegionPreset("阿尔及利亚", "Algeria", "DZ", "60301", "Mobilis", "ar-DZ", "Africa/Algiers", 36.7538, 3.0588),
        RegionPreset("突尼斯", "Tunisia", "TN", "60501", "Ooredoo", "ar-TN", "Africa/Tunis", 36.8065, 10.1815),
        RegionPreset("利比亚", "Libya", "LY", "60601", "Libyana", "ar-LY", "Africa/Tripoli", 32.8872, 13.1913),
        RegionPreset("苏丹", "Sudan", "SD", "63401", "Zain SD", "ar-SD", "Africa/Khartoum", 15.5007, 32.5599),
        RegionPreset("埃塞俄比亚", "Ethiopia", "ET", "63601", "Ethio Telecom", "am-ET", "Africa/Addis_Ababa", 9.0108, 38.7612),
        RegionPreset("坦桑尼亚", "Tanzania", "TZ", "64001", "Vodacom", "sw-TZ", "Africa/Dar_es_Salaam", -6.7924, 39.2083),
        RegionPreset("乌干达", "Uganda", "UG", "64101", "MTN UG", "en-UG", "Africa/Kampala", 0.3476, 32.5825),
        RegionPreset("卢旺达", "Rwanda", "RW", "63501", "MTN RW", "rw-RW", "Africa/Kigali", -1.9441, 30.0619),
        RegionPreset("加纳", "Ghana", "GH", "62001", "MTN GH", "en-GH", "Africa/Accra", 5.6037, -0.1870),
        RegionPreset("塞内加尔", "Senegal", "SN", "60801", "Orange SN", "fr-SN", "Africa/Dakar", 14.7167, -17.4677),
        RegionPreset("科特迪瓦", "Côte d'Ivoire", "CI", "61201", "Orange CI", "fr-CI", "Africa/Abidjan", 5.3599, -4.0083),
        RegionPreset("喀麦隆", "Cameroon", "CM", "62401", "MTN CM", "fr-CM", "Africa/Douala", 4.0511, 9.7679),
        RegionPreset("刚果（金）", "DR Congo", "CD", "63001", "Vodacom CD", "fr-CD", "Africa/Kinshasa", -4.4419, 15.2663),
        RegionPreset("安哥拉", "Angola", "AO", "63101", "Unitel", "pt-AO", "Africa/Luanda", -8.8390, 13.2894),
        RegionPreset("莫桑比克", "Mozambique", "MZ", "64301", "Vodacom MZ", "pt-MZ", "Africa/Maputo", -25.9692, 32.5732),
        RegionPreset("赞比亚", "Zambia", "ZM", "64501", "Airtel ZM", "en-ZM", "Africa/Lusaka", -15.3875, 28.3228),
        RegionPreset("津巴布韦", "Zimbabwe", "ZW", "64801", "Econet", "en-ZW", "Africa/Harare", -17.8252, 31.0335),
        RegionPreset("博茨瓦纳", "Botswana", "BW", "65201", "Mascom", "en-BW", "Africa/Gaborone", -24.6282, 25.9231),
        RegionPreset("纳米比亚", "Namibia", "NA", "64901", "MTC", "en-NA", "Africa/Windhoek", -22.5609, 17.0658),
        RegionPreset("马拉维", "Malawi", "MW", "65001", "TNM", "en-MW", "Africa/Blantyre", -15.7667, 35.0167),
        RegionPreset("毛里求斯", "Mauritius", "MU", "61701", "My.t", "en-MU", "Indian/Mauritius", -20.3484, 57.5522),
        RegionPreset("塞舌尔", "Seychelles", "SC", "63301", "Airtel SC", "fr-SC", "Indian/Mahe", -4.6796, 55.4920),
        RegionPreset("马达加斯加", "Madagascar", "MG", "64601", "Telma", "mg-MG", "Indian/Antananarivo", -18.8792, 47.5079),
        RegionPreset("斐济", "Fiji", "FJ", "54201", "Vodafone FJ", "en-FJ", "Pacific/Fiji", -18.1248, 178.4501),
        RegionPreset("巴布亚新几内亚", "Papua New Guinea", "PG", "53701", "bmobile", "en-PG", "Pacific/Port_Moresby", -9.4438, 147.1803),
        RegionPreset("萨摩亚", "Samoa", "WS", "54901", "Digicel WS", "sm-WS", "Pacific/Apia", -13.7590, -172.1046),
        RegionPreset("汤加", "Tonga", "TO", "53901", "Digicel TO", "to-TO", "Pacific/Tongatapu", -21.1789, -175.1982),
        RegionPreset("所罗门群岛", "Solomon Islands", "SB", "54001", "Our Telekom", "en-SB", "Pacific/Guadalcanal", -9.4456, 159.9729),
        RegionPreset("瓦努阿图", "Vanuatu", "VU", "54101", "TVL", "bi-VU", "Pacific/Efate", -17.7333, 168.3273),

    ).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.englishName })

    private val PRESET_BY_ISO: Map<String, RegionPreset> = ALL_PRESETS.associateBy { it.isoCode.uppercase(java.util.Locale.ROOT) }

    /**
     * 根据 ISO 代号反查对应的推荐系统默认语言标签。
     *
     * @param isoCode 两位国家或地区标准代号。
     * @return 对应的 BCP-47 语言代码（如 ja-JP），未收录时返回 null，不冒充美国语言。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.LocaleHook.refreshConfig`: 运行时跟随地区时解析语言。
     * - `io.github.meiyongai.toki.subtitle.ui.screen.DashboardScreen`: 界面展示跟随地区的语言名称。
     */
    fun resolveLanguageForIso(isoCode: String): String? {
        return findByIso(isoCode)?.defaultLanguage
    }

    /**
     * 根据 ISO 代号反查对应的推荐系统默认时区标识。
     *
     * @param isoCode 两位国家或地区标准代号。
     * @return 对应的 IANA 时区标识（如 Asia/Tokyo），未收录时返回 null，不冒充 UTC 时区。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.TimeZoneHook.refreshConfig`: 运行时跟随地区时解析时区。
     * - `io.github.meiyongai.toki.subtitle.ui.screen.DashboardScreen`: 界面展示跟随地区的时区标识。
     */
    fun resolveTimeZoneForIso(isoCode: String): String? {
        return findByIso(isoCode)?.defaultTimeZone
    }

    /**
     * 根据 ISO 代号反查对应的推荐系统默认纬度坐标。
     *
     * @param isoCode 两位国家或地区标准代号。
     * @return 对应的纬度值（Double），未收录时返回 null，不冒充东京纬度。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.GpsHook.refreshConfig`: 运行时跟随地区时解析经纬度。
     * - `io.github.meiyongai.toki.subtitle.ui.screen.DashboardScreen`: 界面展示跟随地区的坐标值。
     */
    fun resolveLatitudeForIso(isoCode: String): Double? {
        return findByIso(isoCode)?.defaultLatitude
    }

    /**
     * 根据 ISO 代号反查对应的推荐系统默认经度坐标。
     *
     * @param isoCode 两位国家或地区标准代号。
     * @return 对应的经度值（Double），未收录时返回 null，不冒充东京经度。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.GpsHook.refreshConfig`: 运行时跟随地区时解析经纬度。
     * - `io.github.meiyongai.toki.subtitle.ui.screen.DashboardScreen`: 界面展示跟随地区的坐标值。
     */
    fun resolveLongitudeForIso(isoCode: String): Double? {
        return findByIso(isoCode)?.defaultLongitude
    }

    /**
     * 根据 ISO 代码快速查询预设运营商信息。
     *
     * @param isoCode 两位国家或地区标准代号。
     * @return 匹配的预设实体对象，未收录时返回 null。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.SimHook.resolveCarrier`: 运行时根据用户配置查询默认运营商。
     * - `io.github.meiyongai.toki.subtitle.ui.screen.DashboardScreen`: 用户界面根据当前代号反查国家名称。
     */
    fun findByIso(isoCode: String): RegionPreset? {
        return PRESET_BY_ISO[isoCode.trim().uppercase(java.util.Locale.ROOT)]
    }

    /**
     * 返回地区的中文显示名；未收录时回退为英文名或原始代码。
     * @param isoCode 两位国家/地区代码，也兼容非 ISO 的显示名。
     * @return 可用于作者地区显示的中文名称。
     * Callers: AuthorLocationHook、DashboardScreen。
     */
    fun resolveDisplayName(isoCode: String): String {
        val trimmed = isoCode.trim()
        if (trimmed.isEmpty()) return ""
        val preset = findByIso(trimmed)
        if (preset != null) return preset.countryName
        val code = trimmed.uppercase(java.util.Locale.ROOT)
        if (code in java.util.Locale.getISOCountries()) {
            val localized = java.util.Locale.Builder().setRegion(code).build()
                .getDisplayCountry(java.util.Locale.SIMPLIFIED_CHINESE)
            if (localized.isNotBlank() && localized != code) return localized
        }
        // Do not fabricate a country, carrier or subdivision for unknown host values.
        return trimmed
    }

    /**
     * 根据关键字综合模糊搜索预设国家与地区。
     *
     * 支持匹配中文名称、英文名称以及 ISO 代码。
     *
     * @param query 搜索关键词。
     * @return 包含匹配项的预设列表。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.ui.RegionSelectionDialog`: 地区选择弹窗内的即时搜索过滤。
     */
    fun search(query: String): List<RegionPreset> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return ALL_PRESETS
        }
        return ALL_PRESETS.filter { preset ->
            preset.countryName.contains(trimmed, ignoreCase = true) ||
                preset.englishName.contains(trimmed, ignoreCase = true) ||
                preset.isoCode.contains(trimmed, ignoreCase = true)
        }
    }
}
