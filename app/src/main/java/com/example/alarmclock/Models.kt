package com.example.alarmclock

import org.json.JSONArray
import org.json.JSONObject

/** Один день недели в расписании будильника. */
class Day(var on: Boolean, var h: Int, var m: Int)

/** Названия эффектов, схем и поясов: часы сами присылают их в /api/lists. */
class Lists(
    val fx: List<String>,
    val pal: List<String>,
    val tap: List<String>,
    val bg: List<String>,
    val tz: List<String>
) {
    companion object {
        private fun names(a: JSONArray): List<String> = List(a.length()) { a.getString(it) }

        fun parse(text: String): Lists {
            val j = JSONObject(text)
            return Lists(
                names(j.getJSONArray("fx")),
                names(j.getJSONArray("pal")),
                names(j.getJSONArray("tap")),
                names(j.getJSONArray("bg")),
                names(j.getJSONArray("tz"))
            )
        }
    }
}

/** Состояние и настройки часов (то же, что отдаёт и принимает веб-интерфейс). */
class ClockState {
    // --- то, что меняется само (читается раз в секунду) ---
    var time = "--:--:--"
    var date = ""
    var next = ""
    var ip = ""
    var ssid = ""
    var dferr = ""
    var alarm = false
    var wait = 0
    var left = 0
    var ap = false
    var rtc = true
    var valid = true

    // --- настройки ---
    var bri = 40
    var abri = 80
    var fx = 0
    var pal = 0
    var tz = 1
    var aspd = 50
    var cspd = 50
    var tapfx = 0
    var bg = 0
    var bgbri = 35
    var bgspd = 50
    var dur = 10
    var reps = 2
    var gap = 5
    var vol = 15
    var trk = 1
    var tmin = 5
    var tmax = 12

    /** Индекс = день недели, 0 = воскресенье (как в прошивке). */
    val days = Array(7) { Day(false, 7, 0) }

    fun readStatus(j: JSONObject) {
        time = j.optString("time", time)
        date = j.optString("date", date)
        next = j.optString("next", "")
        ip = j.optString("ip", ip)
        dferr = j.optString("dferr", "")
        alarm = j.optInt("alarm") != 0
        wait = j.optInt("wait")
        left = j.optInt("left")
        ap = j.optInt("ap") != 0
        rtc = j.optInt("rtc") != 0
        valid = j.optInt("valid") != 0
    }

    fun readSettings(j: JSONObject) {
        ssid = j.optString("ssid", ssid)
        bri = j.optInt("bri", bri)
        abri = j.optInt("abri", abri)
        fx = j.optInt("fx", fx)
        pal = j.optInt("pal", pal)
        tz = j.optInt("tz", tz)
        aspd = j.optInt("aspd", aspd)
        cspd = j.optInt("cspd", cspd)
        tapfx = j.optInt("tapfx", tapfx)
        bg = j.optInt("bg", bg)
        bgbri = j.optInt("bgbri", bgbri)
        bgspd = j.optInt("bgspd", bgspd)
        dur = j.optInt("dur", dur)
        reps = j.optInt("reps", reps)
        gap = j.optInt("gap", gap)
        vol = j.optInt("vol", vol)
        trk = j.optInt("trk", trk)
        tmin = j.optInt("tmin", tmin)
        tmax = j.optInt("tmax", tmax)
        val arr = j.optJSONArray("days")
        if (arr != null) {
            for (i in 0 until minOf(7, arr.length())) {
                val d = arr.getJSONObject(i)
                days[i].on = d.optInt("on") != 0
                days[i].h = d.optInt("h", 7)
                days[i].m = d.optInt("m", 0)
            }
        }
    }

    /** Поля формы для POST /api/save (те же имена, что в веб-интерфейсе). */
    fun toForm(): Map<String, String> {
        val f = LinkedHashMap<String, String>()
        f["bri"] = bri.toString()
        f["abri"] = abri.toString()
        f["fx"] = fx.toString()
        f["pal"] = pal.toString()
        f["tz"] = tz.toString()
        f["vol"] = vol.toString()
        f["trk"] = trk.toString()
        f["tmin"] = minOf(tmin, tmax).toString()
        f["tmax"] = maxOf(tmin, tmax).toString()
        f["aspd"] = aspd.toString()
        f["cspd"] = cspd.toString()
        f["tapfx"] = tapfx.toString()
        f["bg"] = bg.toString()
        f["bgbri"] = bgbri.toString()
        f["bgspd"] = bgspd.toString()
        f["dur"] = dur.toString()
        f["reps"] = reps.toString()
        f["gap"] = gap.toString()
        for (d in 0 until 7) {
            f["on$d"] = if (days[d].on) "1" else "0"
            f["h$d"] = days[d].h.toString()
            f["m$d"] = days[d].m.toString()
        }
        return f
    }
}
