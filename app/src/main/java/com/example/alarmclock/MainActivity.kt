package com.example.alarmclock

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

private val BG = Color.parseColor("#14161A")
private val CARD = Color.parseColor("#1E2128")
private val TEXT = Color.parseColor("#E8E8EA")
private val MUTED = Color.parseColor("#8B8F98")
private val ACCENT = Color.parseColor("#FFB020")
private val BTN2 = Color.parseColor("#2F3441")
private val LINE = Color.parseColor("#333845")
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout

    private var host: String? = null
    private var state = ClockState()
    private var lists: Lists? = null

    private var discovery: Discovery? = null
    private var scanner: SubnetScanner? = null
    private var connecting = false
    private var mainShown = false
    private var polling = false
    private var pollBusy = false
    private var failCount = 0
    private var savePending = false

    private var clockView: TextView? = null
    private var statusView: TextView? = null
    private var connectStatus: TextView? = null

    private val saveRunnable = Runnable { saveNow() }
    private val scanRunnable = Runnable { startScan() }
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            poll()
            handler.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("alarmclock", MODE_PRIVATE)
        root = FrameLayout(this)
        root.setBackgroundColor(BG)
        root.fitsSystemWindows = true
        setContentView(root)
        val saved = prefs.getString("host", null)
        if (saved != null) connect(saved, true) else showConnect("Ищу часы в сети Wi\u2011Fi…", true)
    }

    override fun onResume() {
        super.onResume()
        val h = host
        if (mainShown && h != null && !connecting) connect(h, false)
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        stopSearch()
        if (savePending) saveNow()
    }

    // ------------------------------------------------------------------ подключение

    private fun cleanHost(s: String): String =
        s.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').trim()

    private fun showConnect(message: String, search: Boolean) {
        stopPolling()
        mainShown = false
        clockView = null
        statusView = null
        root.removeAllViews()

        val box = vbox()
        box.setPadding(dp(24), dp(48), dp(24), dp(24))
        box.gravity = Gravity.CENTER_HORIZONTAL

        val title = TextView(this)
        title.text = "Чизвычайный Будильник"
        title.textSize = 30f
        title.setTextColor(ACCENT)
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        box.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))

        val st = caption(message)
        st.textSize = 15f
        st.gravity = Gravity.CENTER
        connectStatus = st
        box.add(st, 16)

        box.add(btn("Найти часы автоматически", true) { startSearch() }, 24)
        box.add(
            caption("Или введи адрес часов вручную (он виден в мониторе порта Arduino и в списке устройств роутера):"),
            24
        )

        val ip = EditText(this)
        ip.hint = "например, 192.168.0.94"
        ip.setText(host ?: "")
        ip.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        ip.isSingleLine = true
        ip.setTextColor(TEXT)
        ip.setHintTextColor(MUTED)
        ip.background = fieldBg()
        ip.setPadding(dp(12), dp(10), dp(12), dp(10))
        box.add(ip, 8)
        box.add(btn("Подключиться", false) {
            val h = cleanHost(ip.text.toString())
            if (h.isNotEmpty()) connect(h, false)
        }, 8)

        val sv = ScrollView(this)
        sv.addView(box)
        root.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        if (search) startSearch()
    }

    private fun setConnectStatus(s: String) {
        connectStatus?.text = s
    }

    private fun startSearch() {
        stopSearch()
        if (connecting) return
        setConnectStatus("Ищу часы в сети Wi\u2011Fi…")
        discovery = Discovery(this) { ip -> onFound(ip) }.also { it.start() }
        // Запасной способ: если за 3 секунды ничего не нашлось, перебираем адреса в локальной сети
        handler.postDelayed(scanRunnable, 3000)
    }

    private fun stopSearch() {
        handler.removeCallbacks(scanRunnable)
        discovery?.stop()
        discovery = null
        scanner?.cancel()
        scanner = null
    }

    private fun startScan() {
        if (connecting || mainShown) return
        val prefix = SubnetScanner.localPrefix()
        if (prefix == null) {
            setConnectStatus("Телефон не подключён к Wi\u2011Fi. Подключись к той же сети, что и часы.")
            return
        }
        setConnectStatus("Проверяю адреса ${prefix}1–254…")
        val sc = SubnetScanner()
        scanner = sc
        sc.scan(prefix, { ip -> handler.post { onFound(ip) } }, {
            handler.post {
                if (scanner === sc) {
                    scanner = null
                    if (!connecting && !mainShown) {
                        setConnectStatus(
                            "Часы в сети не найдены. Проверь, что они включены и подключены к той же сети Wi\u2011Fi, " +
                                "или введи адрес вручную."
                        )
                    }
                }
            }
        })
    }

    private fun onFound(ip: String) {
        if (connecting || mainShown) return
        connect(ip, false)
    }

    private fun connect(h: String, fromSaved: Boolean) {
        if (connecting) return
        connecting = true
        stopSearch()
        val reload = mainShown
        if (!reload) showConnect("Подключаюсь к $h…", false)
        val base = "http://$h"
        Net.exec({
            val l = Lists.parse(Http.get(base, "/api/lists", 4000))
            val j = JSONObject(Http.get(base, "/api/state", 4000))
            val st = ClockState()
            st.readStatus(j)
            st.readSettings(j)
            Pair(l, st)
        }) { res, err ->
            connecting = false
            if (res != null) {
                host = h
                prefs.edit().putString("host", h).apply()
                lists = res.first
                state = res.second
                failCount = 0
                showMain()
                startPolling()
            } else if (reload) {
                toast("Не удалось обновить настройки: " + (err?.message ?: "нет связи"))
                startPolling()
            } else {
                val why = err?.message ?: ""
                showConnect(("Не удалось подключиться к $h. $why").trim(), fromSaved)
            }
        }
    }

    // ------------------------------------------------------------------ опрос состояния

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun poll() {
        val h = host ?: return
        if (pollBusy) return
        pollBusy = true
        Net.exec({ Http.get("http://$h", "/api/state", 2500) }) { txt, err ->
            pollBusy = false
            if (!polling) return@exec
            if (txt != null) {
                failCount = 0
                try {
                    state.readStatus(JSONObject(txt))
                    updateStatus()
                } catch (e: Exception) {
                }
            } else {
                failCount++
                if (failCount >= 4 && err != null) {
                    showConnect("Связь с часами потеряна. Ищу их в сети…", true)
                }
            }
        }
    }

    private fun updateStatus() {
        clockView?.text = state.time
        val sb = StringBuilder()
        sb.append(state.date).append(". ")
        sb.append(if (state.next.isNotEmpty()) "Ближайший будильник: ${state.next}. " else "Нет включённых будильников. ")
        if (!state.valid) sb.append("Время не установлено! Проверь батарейку DS3231. ")
        if (state.alarm) sb.append("Будильник звонит. ")
        if (state.wait > 0) sb.append("Повтор звонка через ${(state.wait + 59) / 60} мин (осталось повторов: ${state.left}). ")
        if (state.dferr.isNotEmpty()) sb.append("Плеер: ${state.dferr}. ")
        if (state.ap) sb.append("Режим точки доступа: интернета нет, время само не обновится. ")
        if (!state.rtc) sb.append("DS3231 не найден! ")
        statusView?.text = sb.toString().trim()
    }

    // ------------------------------------------------------------------ отправка настроек

    private fun queueSave() {
        savePending = true
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 600)
    }

    private fun saveNow() {
        val h = host ?: return
        handler.removeCallbacks(saveRunnable)
        savePending = false
        val form = state.toForm()
        Net.exec({ Http.post("http://$h", "/api/save", form) }) { _, err ->
            if (err != null) toast("Не удалось сохранить: " + (err.message ?: "нет связи"))
        }
    }

    /** Показать эффект будильника (или реакцию на касание) на матрице на 10 секунд, без звука. */
    private fun previewFx(tap: Boolean) {
        val h = host ?: return
        handler.removeCallbacks(saveRunnable)
        savePending = false
        val form = state.toForm()
        val pv = if (tap) mapOf("fx" to state.fx.toString(), "tap" to "1") else mapOf("fx" to state.fx.toString())
        Net.exec({
            Http.post("http://$h", "/api/save", form)
            Http.post("http://$h", "/api/preview", pv)
        }) { _, err ->
            if (err != null) toast("Ошибка: " + (err.message ?: "нет связи"))
        }
    }

    private fun testAlarm() {
        val h = host ?: return
        handler.removeCallbacks(saveRunnable)
        savePending = false
        val form = state.toForm()
        Net.exec({
            Http.post("http://$h", "/api/save", form)
            Http.post("http://$h", "/api/test")
        }) { _, err ->
            toast(if (err == null) "Тест запущен" else "Ошибка: " + (err.message ?: "нет связи"))
        }
    }

    private fun stopAlarm() {
        val h = host ?: return
        Net.exec({ Http.post("http://$h", "/api/stop") }) { _, err ->
            toast(if (err == null) "Остановлено" else "Ошибка: " + (err.message ?: "нет связи"))
        }
    }

    private fun syncTime() {
        val h = host ?: return
        val c = Calendar.getInstance()
        val p = mapOf(
            "y" to c.get(Calendar.YEAR).toString(),
            "mo" to (c.get(Calendar.MONTH) + 1).toString(),
            "d" to c.get(Calendar.DAY_OF_MONTH).toString(),
            "h" to c.get(Calendar.HOUR_OF_DAY).toString(),
            "mi" to c.get(Calendar.MINUTE).toString(),
            "s" to c.get(Calendar.SECOND).toString()
        )
        Net.exec({ Http.post("http://$h", "/api/settime", p) }) { _, err ->
            toast(if (err == null) "Время установлено" else "Ошибка: " + (err.message ?: "нет связи"))
        }
    }

    private fun wifiDialog() {
        val h = host ?: return
        val box = vbox()
        box.setPadding(dp(20), dp(12), dp(20), 0)
        val ssid = EditText(this)
        ssid.hint = "Имя сети"
        ssid.setText(state.ssid)
        ssid.inputType = InputType.TYPE_CLASS_TEXT
        val pass = EditText(this)
        pass.hint = "Пароль (пусто = не менять)"
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        box.addView(ssid, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(pass, LinearLayout.LayoutParams(MATCH, WRAP))
        AlertDialog.Builder(this)
            .setTitle("Wi\u2011Fi часов")
            .setMessage(
                "Часы перезагрузятся и подключатся к этой сети. Если ошибиться, они включат точку доступа " +
                    "AlarmClock (пароль 12345678)."
            )
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val s = ssid.text.toString().trim()
                if (s.isEmpty()) {
                    toast("Укажи имя сети")
                } else {
                    val p = mapOf("ssid" to s, "pass" to pass.text.toString())
                    Net.exec({ Http.post("http://$h", "/api/wifi", p) }) { _, err ->
                        if (err == null) {
                            showConnect("Часы перезагружаются и подключаются к новой сети. Ищу их…", true)
                        } else {
                            toast("Ошибка: " + (err.message ?: "нет связи"))
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ------------------------------------------------------------------ главный экран

    private fun showMain() {
        val l = lists ?: return
        mainShown = true
        connectStatus = null
        root.removeAllViews()

        val col = vbox()

        val header = vbox()
        header.setPadding(dp(16), dp(10), dp(16), dp(8))
        val clock = TextView(this)
        clock.text = state.time
        clock.textSize = 42f
        clock.setTextColor(ACCENT)
        clock.typeface = Typeface.DEFAULT_BOLD
        clock.gravity = Gravity.CENTER
        val status = caption("")
        status.gravity = Gravity.CENTER
        status.textSize = 13f
        clockView = clock
        statusView = status
        header.addView(clock, LinearLayout.LayoutParams(MATCH, WRAP))
        header.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val body = vbox()
        body.setPadding(dp(12), dp(4), dp(12), dp(28))
        val sv = ScrollView(this)
        sv.addView(body)
        col.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        body.add(daysCard(), 8)
        body.add(clockCard(l), 12)
        body.add(alarmCard(l), 12)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("Тест", true) { testAlarm() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        row.addView(btn("Стоп", false) { stopAlarm() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        body.add(row, 12)

        body.add(timeCard(l), 12)
        body.add(connectionCard(), 12)
        updateStatus()
    }

    private fun daysCard(): View {
        val names = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
        val order = intArrayOf(1, 2, 3, 4, 5, 6, 0)
        return card("Будильник по дням") { c ->
            for (i in 0 until 7) {
                val d = state.days[order[i]]
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                val sw = Switch(this)
                sw.isChecked = d.on
                sw.setOnCheckedChangeListener { _, checked ->
                    d.on = checked
                    queueSave()
                }
                val name = TextView(this)
                name.text = names[i]
                name.textSize = 16f
                name.setTextColor(TEXT)
                val tb = btn(fmtTime(d.h, d.m), false) {}
                tb.setOnClickListener {
                    TimePickerDialog(this, { _, hh, mm ->
                        d.h = hh
                        d.m = mm
                        tb.text = fmtTime(hh, mm)
                        queueSave()
                    }, d.h, d.m, true).show()
                }
                row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP))
                row.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
                row.addView(tb, LinearLayout.LayoutParams(WRAP, WRAP))
                c.add(row, 6)
            }
        }
    }

    private fun clockCard(l: Lists): View = card("Часы") { c ->
        c.add(slider("Яркость часов", 1, 100, state.bri, { "$it%" }) { state.bri = it; queueSave() }, 10)
        c.add(dropdown("Цвет и градиент", l.pal, state.pal) {
            if (state.pal != it) { state.pal = it; queueSave() }
        }, 10)
        c.add(slider("Скорость анимации цвета", 1, 100, state.cspd, ::speedText) { state.cspd = it; queueSave() }, 10)
        c.add(dropdown("Фон за часами (когда будильник не звонит)", l.bg, state.bg) {
            if (state.bg != it) { state.bg = it; queueSave() }
        }, 10)
        c.add(slider("Яркость фона", 1, 100, state.bgbri, { "$it%" }) { state.bgbri = it; queueSave() }, 10)
        c.add(slider("Скорость фона", 1, 100, state.bgspd, ::speedText) { state.bgspd = it; queueSave() }, 10)
    }

    private fun alarmCard(l: Lists): View = card("Будильник") { c ->
        c.add(slider("Яркость при будильнике", 1, 100, state.abri, { "$it%" }) { state.abri = it; queueSave() }, 10)
        c.add(dropdown("Эффект", l.fx, state.fx) {
            if (state.fx != it) { state.fx = it; previewFx(false) }
        }, 10)
        c.add(
            slider("Скорость эффекта", 1, 100, state.aspd, ::speedText, { previewFx(false) }) {
                state.aspd = it
                queueSave()
            }, 10
        )
        c.add(btn("Показать эффект (10 с)", false) { previewFx(false) }, 10)
        c.add(dropdown("Реакция на касание", l.tap, state.tapfx) {
            if (state.tapfx != it) { state.tapfx = it; previewFx(true) }
        }, 14)
        c.add(btn("Показать реакцию (10 с)", false) { previewFx(true) }, 10)
        c.add(slider("Громкость", 0, 30, state.vol, { "$it" }) { state.vol = it; queueSave() }, 14)
        c.add(numberField("Номер трека (0001.mp3 = 1)", state.trk, 1, 3000) { state.trk = it; queueSave() }, 12)

        val taps = LinearLayout(this)
        taps.orientation = LinearLayout.HORIZONTAL
        taps.addView(
            numberField("Касаний для отключения: от", state.tmin, 1, 50) { state.tmin = it; queueSave() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        )
        taps.addView(
            numberField("до", state.tmax, 1, 50) { state.tmax = it; queueSave() },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) }
        )
        c.add(taps, 12)

        c.add(numberField("Длительность звонка, минут (1–30)", state.dur, 1, 30) { state.dur = it; queueSave() }, 12)
        c.add(numberField("Повторов, если не отключили (0 = без повторов)", state.reps, 0, 5) { state.reps = it; queueSave() }, 12)
        c.add(numberField("Пауза перед повтором, минут (1–30)", state.gap, 1, 30) { state.gap = it; queueSave() }, 12)
    }

    private fun timeCard(l: Lists): View = card("Время") { c ->
        c.add(dropdown("Часовой пояс", l.tz, state.tz) {
            if (state.tz != it) { state.tz = it; queueSave() }
        }, 10)
        c.add(
            caption("При подключении к интернету время обновляется само и записывается в часы раз в час."),
            10
        )
        c.add(btn("Взять время с телефона", false) { syncTime() }, 10)
    }

    private fun connectionCard(): View = card("Подключение") { c ->
        c.add(caption("Часы: ${host ?: ""}"), 8)
        c.add(btn("Wi\u2011Fi часов…", false) { wifiDialog() }, 10)
        c.add(btn("Подключиться к другим часам", false) {
            showConnect("Ищу часы в сети Wi\u2011Fi…", true)
        }, 8)
    }

    // ------------------------------------------------------------------ элементы интерфейса

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun LinearLayout.add(v: View, top: Int = 8) {
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(top)
        addView(v, lp)
    }

    private fun fmtTime(h: Int, m: Int): String = String.format(Locale.US, "%02d:%02d", h, m)

    private fun speedText(v: Int): String =
        String.format(Locale.US, "%.1f×", 0.25 * Math.pow(16.0, (v - 1) / 99.0))

    private fun caption(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(MUTED)
        return t
    }

    private fun fieldBg(): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(BG)
        g.cornerRadius = dp(8).toFloat()
        g.setStroke(dp(1), LINE)
        return g
    }

    private fun card(title: String, build: (LinearLayout) -> Unit): View {
        val c = vbox()
        c.setPadding(dp(14), dp(12), dp(14), dp(14))
        val bg = GradientDrawable()
        bg.setColor(CARD)
        bg.cornerRadius = dp(12).toFloat()
        c.background = bg
        val t = TextView(this)
        t.text = title
        t.textSize = 17f
        t.setTextColor(TEXT)
        t.typeface = Typeface.DEFAULT_BOLD
        c.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        build(c)
        return c
    }

    private fun btn(text: String, primary: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 15f
        b.setTextColor(if (primary) BG else TEXT)
        val g = GradientDrawable()
        g.setColor(if (primary) ACCENT else BTN2)
        g.cornerRadius = dp(10).toFloat()
        b.background = g
        b.setOnClickListener { onClick() }
        return b
    }

    private fun slider(
        title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String,
        onRelease: (() -> Unit)? = null, onChange: (Int) -> Unit
    ): View {
        val box = vbox()
        val tv = caption("$title: ${fmt(value)}")
        tv.setTextColor(TEXT)
        box.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = value.coerceIn(min, max) - min
        sb.progressTintList = ColorStateList.valueOf(ACCENT)
        sb.thumbTintList = ColorStateList.valueOf(ACCENT)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + min
                tv.text = "$title: ${fmt(v)}"
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onRelease?.invoke()
            }
        })
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(2)
        box.addView(sb, lp)
        return box
    }

    private fun dropdown(title: String, options: List<String>, selected: Int, onSel: (Int) -> Unit): View {
        val box = vbox()
        val cap = caption(title)
        cap.setTextColor(TEXT)
        box.addView(cap, LinearLayout.LayoutParams(MATCH, WRAP))
        val sp = Spinner(this)
        sp.background = fieldBg()
        sp.setPadding(dp(8), dp(6), dp(8), dp(6))
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        sp.setSelection(selected.coerceIn(0, maxOf(0, options.size - 1)), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSel(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val lp = LinearLayout.LayoutParams(MATCH, dp(48))
        lp.topMargin = dp(4)
        box.addView(sp, lp)
        return box
    }

    private fun numberField(title: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val box = vbox()
        val cap = caption(title)
        cap.setTextColor(TEXT)
        box.addView(cap, LinearLayout.LayoutParams(MATCH, WRAP))
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(value.toString())
        et.setTextColor(TEXT)
        et.background = fieldBg()
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        et.isSingleLine = true
        var current = value
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                current = v.coerceIn(min, max)
                onChange(current)
            }
        })
        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) et.setText(current.toString())
        }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(4)
        box.addView(et, lp)
        return box
    }
}
