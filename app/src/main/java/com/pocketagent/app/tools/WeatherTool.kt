package com.pocketagent.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 天气查询,使用 Open-Meteo 免费 API(无需 key)。
 * 先地理编码城市名,再查实时天气;days>=2 时附带未来几天预报。
 * 返回原始数据,由 LLM 组织成自然语言。
 */
class GetWeatherTool : Tool {
    override val name = "get_weather"
    override val description =
        "查询指定城市的天气。默认只查实时天气;用户问未来天气时传 days(1~7,含今天)获取逐日预报。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("city") {
                put("type", "string")
                put("description", "城市名,支持中文,如\"北京\"、\"杭州\"")
            }
            putJsonObject("days") {
                put("type", "integer")
                put("description", "预报天数 1~7(含今天),默认 1 表示仅实时天气;用户问明天传 2,问未来一周传 7")
            }
        }
        putJsonArray("required") { add("city") }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: JsonObject, context: ToolContext): String =
        withContext(Dispatchers.IO) {
            val city = args["city"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (city.isEmpty()) return@withContext "错误: 缺少 city 参数"
            val days = (args["days"]?.jsonPrimitive?.int ?: 1).coerceIn(1, 7)

            // 1) 城市名 → 经纬度
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=${java.net.URLEncoder.encode(city, "UTF-8")}" +
                "&count=1&language=zh&format=json"
            val geoText = httpGet(geoUrl) ?: return@withContext "错误: 地理编码服务不可用"
            val geo = json.parseToJsonElement(geoText).jsonObject
            val first = geo["results"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@withContext "错误: 没有找到城市 \"$city\""
            val lat = first["latitude"]!!.jsonPrimitive.double
            val lon = first["longitude"]!!.jsonPrimitive.double
            val resolved = first["name"]?.jsonPrimitive?.content ?: city

            // 2) 查天气
            val wxUrl = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=$days"
            val wxText = httpGet(wxUrl) ?: return@withContext "错误: 天气服务不可用"
            val wx = json.parseToJsonElement(wxText).jsonObject
            val current = wx["current"]!!.jsonObject

            val temp = current["temperature_2m"]!!.jsonPrimitive.double
            val feels = current["apparent_temperature"]!!.jsonPrimitive.double
            val humidity = current["relative_humidity_2m"]!!.jsonPrimitive.int
            val wind = current["wind_speed_10m"]!!.jsonPrimitive.double
            val desc = weatherCodeText(current["weather_code"]!!.jsonPrimitive.int)

            val sb = StringBuilder()
            sb.append("$resolved 当前天气:$desc,气温 ${temp}°C(体感 ${feels}°C),湿度 $humidity%,风速 ${wind}km/h")

            // 3) 逐日预报
            val daily = wx["daily"]?.jsonObject
            if (days >= 2 && daily != null) {
                val dates = daily["time"]?.jsonArray ?: return@withContext sb.toString()
                sb.append("。未来 ${dates.size} 天预报:")
                dates.forEachIndexed { i, d ->
                    val date = d.jsonPrimitive.content
                    val code = daily["weather_code"]?.jsonArray?.get(i)?.jsonPrimitive?.int ?: 0
                    val max = daily["temperature_2m_max"]?.jsonArray?.get(i)?.jsonPrimitive?.double
                    val min = daily["temperature_2m_min"]?.jsonArray?.get(i)?.jsonPrimitive?.double
                    val rain = daily["precipitation_probability_max"]?.jsonArray?.get(i)?.jsonPrimitive?.int
                    sb.append("$date ${weatherCodeText(code)} ${min}~${max}°C")
                    if (rain != null) sb.append("(降水概率${rain}%)")
                    sb.append(";")
                }
            }
            sb.toString()
        }

    private fun httpGet(url: String): String? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: java.io.IOException) {
        null
    }

    private fun weatherCodeText(code: Int) = when (code) {
        0 -> "晴"
        1, 2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61, 63, 65 -> "雨(小/中/大)"
        66, 67 -> "冻雨"
        71, 73, 75 -> "雪(小/中/大)"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨(小/中/大)"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> "未知天气(code=$code)"
    }
}
