package com.aktv.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.*
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.*
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class VH(v: View) : RecyclerView.ViewHolder(v)

class MainActivity : AppCompatActivity() {
    /** key is unique across V1 + V2 (V2 keys are prefixed), id is what the API expects. */
    data class Channel(val key: String, val id: String, val name: String, val category: String, val logo: String, val v2: Boolean = false)
    data class Site(val name: String, val url: String, val c1: Int, val c2: Int)
    class Server(val label: String, val url: String, val hls: Boolean)
    class Stream(val servers: List<Server>, val drm: JSONObject?)
    sealed class Tile {
        data class Ch(val c: Channel) : Tile()
        data class St(val s: Site) : Tile()
        object Hint : Tile()
    }
    class Row(val title: String, var tiles: List<Tile>)

    // ➜ ADD MORE WEBSITES HERE (name, link, gradient colours)
    private val SITES = listOf(
        Site("Net77", "https://net77.cc/home", 0xFFE50914.toInt(), 0xFF5A0A12.toInt()),
        Site("AethoFlix", "https://www.aethoflix.world/", 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt())
    )
    private val SITE = "https://livetgtv.lovable.app"
    // Optional geo-block / IP-block bypass: deploy tools/cloudflare_worker.js, then fill these in.
    // Leave WORKER empty to disable. Example: "https://aktv-proxy.yourname.workers.dev"
    private val WORKER = ""
    private val WORKER_KEY = ""
    private val BASE = "$SITE/api/public/channels"
    private val BASE2 = "$SITE/api/public/v2"
    private val GUIDE = "$SITE/api/public/guide"
    private val UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 Chrome/110 Safari/537.36"
    private val io = Executors.newFixedThreadPool(4)
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())
    private val fRegular by lazy { ResourcesCompat.getFont(this, R.font.sora_regular) ?: Typeface.DEFAULT }
    private val fSemi by lazy { ResourcesCompat.getFont(this, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD }
    private val fLight by lazy { ResourcesCompat.getFont(this, R.font.sora_light) ?: Typeface.DEFAULT }

    private var all = listOf<Channel>(); private var allV2 = listOf<Channel>()
    private var v2Loaded = false; private var v2Loading = false
    private var shown = listOf<Channel>()
    private var rows = listOf<Row>(); private val inner = HashMap<Int, TileAdapter>()
    private var favs = setOf<String>(); private var heroTile: Tile? = null
    private val accents = HashMap<String, Int>()
    private val tilePool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 24); setMaxRecycledViews(1, 6); setMaxRecycledViews(2, 2) }
    private var tab = 0; private var current = -1; private var retries = 0
    private var player: ExoPlayer? = null
    // TG embed is rendered inline inside AKTV playerLayer using GeckoView (not Android System WebView).
    private var embedView: GeckoView? = null
    private var embedSession: GeckoSession? = null
    private var stream: Stream? = null; private var serverIdx = 0; private var profile = 0; private var token = 0
    private var uiAccent = Accent.SIGNAL

    private lateinit var rowsView: RecyclerView; private lateinit var status: TextView
    private lateinit var layer: FrameLayout; private lateinit var pv: PlayerView; private lateinit var spinner: ProgressBar
    private lateinit var segs: List<TextView>
    private lateinit var segIndicator: View; private val segPill = GradientDrawable()
    private lateinit var btnSearch: TextView; private lateinit var splash: SplashView
    private lateinit var aurora: AuroraView
    private lateinit var heroText: View; private lateinit var heroDot: View; private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView; private lateinit var heroChips: LinearLayout
    private lateinit var heroOrb: View; private lateinit var heroLogo: ImageView; private lateinit var heroMono: TextView
    private val orbGlass by lazy { GlassDrawable(dp) }
    private val heroGlowD = GlowDrawable()
    private lateinit var infoCard: View; private lateinit var infoLogo: ImageView; private lateinit var infoName: TextView
    private lateinit var infoCat: TextView; private lateinit var infoNow: TextView
    private lateinit var playerScrim: View; private lateinit var playerClock: View
    private val scrimGrad = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x66000000, 0x00000000))
    private val infoGlass by lazy { GlassDrawable(dp, 24f).apply { base = Color.argb(150, 12, 14, 32); hold(0.5f) } }
    private val guideGlass by lazy { GlassDrawable(dp, 28f).apply { base = Color.argb(185, 10, 12, 28); hold(0.4f) } }
    private val serverGlass by lazy { GlassDrawable(dp, 24f).apply { base = Color.argb(190, 10, 12, 28); hold(0.45f) } }

    // home / guide
    private val FAV = "Favorites"
    private lateinit var home: ViewGroup; private var homeFocusMode = 0; private var lastHomeFocus: View? = null
    private lateinit var guide: ViewGroup; private lateinit var guideScrim: View; private lateinit var guideDivider: View
    private lateinit var catList: RecyclerView; private lateinit var chList: RecyclerView
    private lateinit var chCol: View; private lateinit var chHeader: TextView; private lateinit var chSearch: TextView
    private var guideShown = false; private var shownCat = ""
    private var guideCats = listOf<String>(); private var catCounts = mapOf<String, Int>()
    private var guideCat = ""; private var guideChannels = listOf<Channel>(); private var categoryChannels = listOf<Channel>()

    // servers
    private lateinit var serverPanel: ViewGroup; private lateinit var serverList: RecyclerView
    private var serverShown = false; private var serverRows = listOf<String>()

    // search
    private lateinit var searchView: ViewGroup; private lateinit var searchQuery: TextView; private lateinit var kbd: LinearLayout
    private lateinit var searchHeader: TextView; private lateinit var searchList: RecyclerView
    private var searchShown = false; private val searchQ = StringBuilder(); private var searchResults = listOf<Channel>()
    private var kbAccent = Accent.SIGNAL

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // TV: never let the screensaver / sleep kick in while the app is open (channels play for hours)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        rowsView = findViewById(R.id.rows); status = findViewById(R.id.status)
        layer = findViewById(R.id.playerLayer); pv = findViewById(R.id.playerView); spinner = findViewById(R.id.spinner)
        segs = listOf<TextView>(findViewById<TextView>(R.id.segLive), findViewById<TextView>(R.id.seg247), findViewById<TextView>(R.id.segSites))
        segIndicator = findViewById(R.id.segIndicator); btnSearch = findViewById(R.id.btnSearch); splash = findViewById(R.id.splash)
        aurora = findViewById(R.id.aurora)
        heroText = findViewById(R.id.heroText); heroDot = findViewById(R.id.heroDot); heroBadge = findViewById(R.id.heroBadge)
        heroTitle = findViewById(R.id.heroTitle); heroChips = findViewById(R.id.heroChips)
        heroOrb = findViewById(R.id.heroOrb); heroLogo = findViewById(R.id.heroLogo); heroMono = findViewById(R.id.heroMono)
        infoCard = findViewById(R.id.infoCard); infoLogo = findViewById(R.id.infoLogo); infoName = findViewById(R.id.infoName)
        infoCat = findViewById(R.id.infoCat); infoNow = findViewById(R.id.infoNow)
        playerScrim = findViewById(R.id.playerScrim); playerClock = findViewById(R.id.playerClock)

        // brand: bold AK + light tv
        findViewById<TextView>(R.id.brandText).text = SpannableStringBuilder("AK").apply {
            val s = length; append("tv")
            setSpan(TypefaceSpan(fLight), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Accent.ION), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // glass surfaces
        findViewById<View>(R.id.segTrack).background = GlassDrawable(dp).apply { base = Color.argb(28, 255, 255, 255) }
        segPill.cornerRadius = 40 * dp; segIndicator.background = segPill
        heroOrb.background = orbGlass
        findViewById<View>(R.id.heroGlow).background = heroGlowD
        infoCard.background = infoGlass; playerScrim.background = scrimGrad
        findViewById<View>(R.id.infoLogoBox).background = GlassDrawable(dp, 16f)
        findViewById<TextView>(R.id.infoHint).text = SpannableStringBuilder().apply {
            key(this, "▲ ▼", "Channel"); append("     "); key(this, "OK", "Servers / Reload"); append("     "); key(this, "R / Red", "Reload"); append("     "); key(this, "▶", "All channels"); append("     "); key(this, "Back", "Exit")
        }
        val sg = GlassDrawable(dp).apply { base = Color.argb(28, 255, 255, 255) }
        btnSearch.background = sg
        btnSearch.setOnFocusChangeListener { _, f -> sg.focused = f; btnSearch.setTextColor(if (f) Accent.TEXT else Accent.DIM) }
        btnSearch.setOnClickListener { openSearch() }

        home = findViewById(R.id.home); homeFocusMode = home.descendantFocusability
        guide = findViewById(R.id.guide); guideScrim = findViewById(R.id.guideScrim); guideDivider = findViewById(R.id.guideDivider)
        catList = findViewById(R.id.catList); chList = findViewById(R.id.chList)
        chCol = findViewById(R.id.chCol); chHeader = findViewById(R.id.chHeader); chSearch = findViewById(R.id.chSearch)
        guide.background = guideGlass
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        listOf(catList to catAdapter, chList to chAdapter).forEach { (rv, ad) ->
            rv.layoutManager = LinearLayoutManager(this); rv.adapter = ad; rv.itemAnimator = null
            rv.overScrollMode = View.OVER_SCROLL_NEVER
        }
        val csg = GlassDrawable(dp, 14f).apply { base = Color.argb(30, 255, 255, 255) }
        chSearch.background = csg
        chSearch.setOnFocusChangeListener { _, f -> csg.focused = f; chSearch.setTextColor(if (f) Accent.TEXT else Accent.DIM) }
        chSearch.setOnClickListener { openSearch() }

        serverPanel = findViewById(R.id.serverPanel); serverList = findViewById(R.id.serverList)
        serverPanel.background = serverGlass
        serverPanel.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        serverList.layoutManager = LinearLayoutManager(this); serverList.adapter = serverAdapter; serverList.itemAnimator = null

        searchView = findViewById(R.id.search); searchQuery = findViewById(R.id.searchQuery); kbd = findViewById(R.id.kbd)
        searchHeader = findViewById(R.id.searchHeader); searchList = findViewById(R.id.searchList)
        searchView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        searchQuery.background = GlassDrawable(dp, 16f).apply { base = Color.argb(34, 255, 255, 255) }
        searchList.layoutManager = LinearLayoutManager(this); searchList.adapter = srAdapter; searchList.itemAnimator = null
        buildKeyboard()

        favs = prefs.getStringSet("favs", emptySet())!!.toSet()
        rowsView.layoutManager = SnapLayoutManager(this, RecyclerView.VERTICAL); rowsView.adapter = rowAdapter
        rowsView.itemAnimator = null; rowsView.setHasFixedSize(true); rowsView.setItemViewCacheSize(4)
        rowsView.overScrollMode = View.OVER_SCROLL_NEVER
        rowsView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> dimRows() }
        rowsView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) aurora.resume() else aurora.pause()
            }
        })
        findViewById<TextView>(R.id.versionV2).setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", "$SITE/"))
        }
        segs.forEachIndexed { i, tv ->
            tv.setOnClickListener { switchTab(i) }
            tv.setOnFocusChangeListener { _, f -> if (f) switchTab(i); styleSegs() }
        }
        segs[0].post { moveIndicator(false) }
        styleSegs(); fillHero(null); loadChannels()

        splash.onDone = { rowsView.requestFocus(); aurora.resume() }
        splash.start()
    }

    // ---------- helpers ----------
    private fun key(sb: SpannableStringBuilder, k: String, label: String) {
        val s = sb.length; sb.append(k)
        sb.setSpan(ForegroundColorSpan(Accent.TEXT), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan(fSemi), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ").append(label)
    }
    private fun keyHint(k: String, label: String) = SpannableStringBuilder().also { key(it, k, label) }

    private fun chip(text: CharSequence) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(Accent.DIM); typeface = fRegular
        setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
        background = GlassDrawable(dp)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (10 * dp).toInt() }
    }

    private fun initials(n: String): String {
        val w = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val s = if (w.size >= 2) "${w[0].first()}${w[1].first()}" else n.trim().take(2)
        return s.uppercase()
    }
    /** Loads a logo, or shows initials when a channel has none. */
    private fun bindLogo(img: ImageView, mono: TextView?, ch: Channel) {
        if (ch.logo.isBlank()) { img.setImageDrawable(null); img.visibility = View.INVISIBLE; mono?.visibility = View.VISIBLE; mono?.text = initials(ch.name) }
        else { img.visibility = View.VISIBLE; mono?.visibility = View.GONE; img.load(ch.logo) }
    }

    private fun styleSegs() {
        val segFocused = segs.any { it.isFocused }
        segPill.setColor(if (segFocused) Color.WHITE else Color.argb(40, 255, 255, 255))
        segs.forEachIndexed { i, tv ->
            tv.setTextColor(when { tv.isFocused -> Accent.ABYSS; tab == i -> Accent.TEXT; else -> Accent.DIM })
        }
    }
    /** The selected-tab pill slides between tabs. */
    private fun moveIndicator(animate: Boolean) {
        val target = segs[tab]
        if (target.width == 0) { target.post { moveIndicator(false) }; return }
        val lp = segIndicator.layoutParams
        val x0 = segIndicator.translationX; val w0 = lp.width
        val x1 = target.left.toFloat(); val w1 = target.width
        if (!animate || w0 <= 0) { segIndicator.translationX = x1; lp.width = w1; segIndicator.layoutParams = lp; return }
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320; interpolator = Ease
            addUpdateListener {
                val f = it.animatedValue as Float
                segIndicator.translationX = x0 + (x1 - x0) * f
                lp.width = (w0 + (w1 - w0) * f).toInt(); segIndicator.layoutParams = lp
            }
            start()
        }
    }

    private fun focusAnim(x: View, f: Boolean) {
        val s = if (f) 1.08f else 1f
        x.animate().setStartDelay(0).scaleX(s).scaleY(s).setDuration(if (f) 220 else 260).setInterpolator(Ease).start()
    }
    /** Rows without focus step back so the active row reads first. */
    private fun dimRows() {
        val inRows = rowsView.hasFocus()
        for (i in 0 until rowsView.childCount) {
            val c = rowsView.getChildAt(i)
            val a = if (!inRows || c.hasFocus()) 1f else 0.45f
            if (c.alpha != a) c.animate().alpha(a).setDuration(220).start()
            c.findViewById<TextView>(R.id.rowTitle)?.setTextColor(if (inRows && c.hasFocus()) Accent.TEXT else Accent.DIM)
        }
    }
    private fun isInside(v: View, parent: View): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) { if (p === parent) return true; p = p.parent }
        return false
    }
    private fun accentOf(key: String) = accents[key] ?: Accent.SIGNAL
    private fun extractAccent(key: String, d: Drawable, cb: (Int) -> Unit) {
        accents[key]?.let { cb(it); return }
        Accent.from(d) { c -> accents[key] = c; cb(c) }
    }
    private fun ensureAccent(ch: Channel, cb: (Int) -> Unit) {
        accents[ch.key]?.let { cb(it); return }
        if (ch.logo.isBlank()) { cb(Accent.SIGNAL); return }
        val req = ImageRequest.Builder(this).data(ch.logo).allowHardware(false).size(128)
            .target(onSuccess = { d -> extractAccent(ch.key, d, cb) }, onError = { cb(Accent.SIGNAL) }).build()
        imageLoader.enqueue(req)
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", UA)
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun clean(s: String?) = if (s.isNullOrBlank() || s == "null") "" else s

    // ---------- data ----------
    private fun loadChannels() {
        status.visibility = View.VISIBLE; status.text = "Loading channels…"
        io.execute {
            try {
                val arr = JSONObject(get(BASE)).getJSONArray("channels")
                val list = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it); val id = o.getString("id")
                    Channel(id, id, o.optString("name", id), clean(o.optString("category", "")).ifEmpty { "Other" }, clean(o.optString("logo", "")))
                }
                runOnUiThread {
                    all = list; status.text = ""; status.visibility = View.GONE
                    if (tab == 0) { refreshRows(); rowsView.post { rowsView.requestFocus() } }
                    if (searchShown) updateSearch()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Channels didn't load (${e.message}). Check the connection and reopen the app." } }
        }
    }

    private fun loadV2() {
        if (v2Loading || v2Loaded) return
        v2Loading = true
        io.execute {
            try {
                val arr = JSONObject(get("$BASE2/channels")).getJSONArray("channels")
                val list = ArrayList<Channel>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i); val id = clean(o.optString("id", "")); if (id.isEmpty()) continue
                    val name = clean(o.optString("name", "")).ifEmpty { "Channel $id" }
                    val logo = listOf("logo", "image", "icon", "thumbnail", "poster").map { clean(o.optString(it, "")) }.firstOrNull { it.isNotEmpty() } ?: ""
                    val cat = clean(o.optString("category", "")).ifEmpty {
                        val ch = name.firstOrNull { it.isLetterOrDigit() }
                        if (ch == null) "#" else if (ch.isDigit()) "0-9" else ch.uppercaseChar().toString()
                    }
                    list.add(Channel("v2_$id", id, name, cat, logo, true))
                }
                runOnUiThread {
                    allV2 = list; v2Loaded = true; v2Loading = false
                    if (tab == 1) { status.text = ""; status.visibility = View.GONE; refreshRows() }
                    if (searchShown) updateSearch()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    v2Loading = false
                    if (tab == 1) { status.visibility = View.VISIBLE; status.text = "24/7 channels didn't load (${e.message}). Move away and back to retry." }
                }
            }
        }
    }

    private fun catalog() = if (tab == 1) allV2 else all
    private fun playingCatalog() = if (shown.firstOrNull()?.v2 == true) allV2 else all

    private fun favTiles(): List<Tile> {
        val l = catalog().filter { it.key in favs }.map { Tile.Ch(it) }
        return if (l.isEmpty()) listOf(Tile.Hint) else l
    }

    private fun refreshRows() {
        rows = when (tab) {
            0 -> listOf(Row(FAV, favTiles())) + all.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            1 -> if (allV2.isEmpty()) emptyList() else listOf(Row(FAV, favTiles())) + allV2.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            else -> listOf(Row("Movies & series", SITES.map { Tile.St(it) }))
        }
        inner.clear()
        rowAdapter.notifyDataSetChanged()
        setHero(when (tab) { 0 -> all.firstOrNull()?.let { Tile.Ch(it) }; 1 -> allV2.firstOrNull()?.let { Tile.Ch(it) }; else -> Tile.St(SITES[0]) }, immediate = true)
    }

    private fun toggleFav(c: Channel) {
        val s = favs.toMutableSet(); val added = s.add(c.key); if (!added) s.remove(c.key)
        favs = s; prefs.edit().putStringSet("favs", s).apply()
        Toast.makeText(this, if (added) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        for ((ri, ad) in inner) if (ri > 0 && ri < rows.size) {
            val idx = rows[ri].tiles.indexOfFirst { it is Tile.Ch && it.c.key == c.key }
            if (idx >= 0) ad.notifyItemChanged(idx, "fav")
        }
        if (tab != 2 && rows.isNotEmpty() && rows[0].title == FAV) { rows[0].tiles = favTiles(); rowAdapter.notifyItemChanged(0) }
        (heroTile as? Tile.Ch)?.let { if (it.c.key == c.key) fillHero(it) }
    }

    private fun switchTab(t: Int) {
        if (t == tab) return
        val dir = if (t > tab) 1 else -1
        tab = t; styleSegs(); moveIndicator(true)
        if (t == 1 && !v2Loaded) { status.visibility = View.VISIBLE; status.text = "Loading 24/7 channels…"; loadV2() }
        else if (all.isNotEmpty()) status.visibility = View.GONE
        val shift = 48 * dp * dir
        rowsView.animate().alpha(0f).translationX(-shift).setDuration(140).withEndAction {
            refreshRows(); rowsView.scrollToPosition(0)
            rowsView.translationX = shift
            rowsView.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(Ease).start()
        }.start()
    }

    // ---------- hero ----------
    private val heroRunnable = Runnable { applyHero(heroTile) }
    private fun setHero(t: Tile?, immediate: Boolean = false) {
        heroTile = t; ui.removeCallbacks(heroRunnable)
        if (immediate) fillHero(t) else ui.postDelayed(heroRunnable, 110)   // debounce fast scrolling
    }
    private fun applyHero(t: Tile?) {
        heroText.animate().cancel()
        heroText.animate().alpha(0f).translationX(-10 * dp).setDuration(100).withEndAction {
            fillHero(t); heroText.translationX = 16 * dp
            heroText.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(Ease).start()
        }.start()
        heroOrb.scaleX = 0.94f; heroOrb.scaleY = 0.94f
        heroOrb.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Ease).start()
    }
    private fun glowHero(c: Int, partner: Int) {
        aurora.setPalette(c, partner); orbGlass.accent = c; heroGlowD.animateTo(c)
    }
    private fun fillHero(t: Tile?) {
        heroChips.removeAllViews()
        when (t) {
            is Tile.Ch -> {
                heroDot.visibility = View.VISIBLE; heroDot.backgroundTintList = ColorStateList.valueOf(Accent.LIVE)
                heroBadge.text = if (t.c.v2) "24/7 channel" else "Live now"; heroTitle.text = t.c.name
                heroChips.addView(chip(t.c.category))
                heroChips.addView(chip(keyHint("OK", "Watch")))
                heroChips.addView(chip(keyHint("Hold OK", if (t.c.key in favs) "Remove from favorites" else "Add to favorites")))
                if (t.c.logo.isBlank()) {
                    heroLogo.visibility = View.GONE; heroMono.visibility = View.VISIBLE; heroMono.text = initials(t.c.name)
                    glowHero(Accent.SIGNAL, Accent.INDIGO)
                } else {
                    heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE
                    heroLogo.load(t.c.logo) {
                        allowHardware(false); crossfade(true)
                        listener(onSuccess = { _, r ->
                            extractAccent(t.c.key, r.drawable) { c -> if ((heroTile as? Tile.Ch)?.c?.key == t.c.key) glowHero(c, Accent.partner(c)) }
                        })
                    }
                    accents[t.c.key]?.let { glowHero(it, Accent.partner(it)) }
                }
            }
            is Tile.St -> {
                heroDot.visibility = View.VISIBLE; heroDot.backgroundTintList = ColorStateList.valueOf(t.s.c1)
                heroBadge.text = "Movies & series"; heroTitle.text = t.s.name
                heroChips.addView(chip(Uri.parse(t.s.url).host ?: t.s.url))
                heroChips.addView(chip(keyHint("OK", "Open")))
                heroChips.addView(chip("Arrows move the pointer"))
                heroLogo.visibility = View.GONE; heroMono.visibility = View.VISIBLE; heroMono.text = t.s.name.take(1)
                glowHero(t.s.c1, t.s.c2)
            }
            else -> {
                heroDot.visibility = View.GONE; heroBadge.text = ""; heroTitle.text = "AKtv"
                heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE; heroLogo.setImageResource(R.drawable.ic_mark)
                glowHero(Accent.SIGNAL, Accent.INDIGO)
            }
        }
    }

    // ---------- player overlay ----------
    private val hideInfo = Runnable {
        infoCard.animate().alpha(0f).translationY(14 * dp).setDuration(450).start()
        playerScrim.animate().alpha(0f).setDuration(450).start()
    }
    private fun showInfo() {
        ui.removeCallbacks(hideInfo)
        infoCard.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(Ease).start()
        playerScrim.animate().alpha(1f).setDuration(260).start()
        playerClock.alpha = 1f
        ui.postDelayed(hideInfo, 4500)
    }
    private fun refreshInfoCat() {
        val ch = shown.getOrNull(current) ?: return
        val st = stream
        val srv = if (st != null && st.servers.size > 1) "   ·   ${st.servers[serverIdx.coerceIn(0, st.servers.size - 1)].label}" else ""
        infoCat.text = "${ch.category}   ${current + 1} of ${shown.size}$srv"
    }
    /** Re-colours every player surface from the channel's logo colour. */
    private fun tintPlayer(c: Int) {
        uiAccent = c; kbAccent = c
        infoGlass.accent = c; guideGlass.accent = c; serverGlass.accent = c
        spinner.indeterminateTintList = ColorStateList.valueOf(c)
        val dark = ColorUtils.blendARGB(Color.BLACK, c, 0.24f)
        scrimGrad.setColors(intArrayOf(ColorUtils.setAlphaComponent(dark, 0xE6), ColorUtils.setAlphaComponent(dark, 0x66), 0x00000000))
        aurora.setPalette(c, Accent.partner(c))
    }
    private fun bindInfo(ch: Channel) {
        infoName.text = ch.name; infoNow.visibility = View.GONE
        refreshInfoCat(); bindLogo(infoLogo, null, ch)
        ensureAccent(ch) { c -> if (shown.getOrNull(current)?.key == ch.key) tintPlayer(c) }
        showInfo()
    }
    private fun progTitle(o: JSONObject, k: String): String = when (val v = o.opt(k)) {
        is JSONObject -> clean(v.optString("title", "")); is String -> clean(v); else -> ""
    }
    /** Now / next from the TG TV programme guide (V1 channels). Silent if unavailable. */
    private fun fetchProgramme(ch: Channel, my: Int) {
        if (ch.v2) return
        io.execute {
            try {
                val o = JSONObject(get("$GUIDE/${ch.id}"))
                val now = progTitle(o, "nowPlaying"); val next = progTitle(o, "upNext")
                val line = listOf(if (now.isNotEmpty()) "Now  $now" else "", if (next.isNotEmpty()) "Next  $next" else "").filter { it.isNotEmpty() }.joinToString("     ")
                if (line.isNotEmpty()) runOnUiThread { if (my == token) { infoNow.text = line; infoNow.visibility = View.VISIBLE } }
            } catch (e: Exception) { }
        }
    }

    // ---------- playback ----------
    private fun hexToB64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
    private fun looksHls(u: String) = u.contains(".m3u8", true) || u.contains("/proxy", true)

    /** V1 → signed DASH sources (+ClearKey). V2 → every working HLS server. */
    private fun resolve(ch: Channel): Stream {
        if (!ch.v2) {
            val o = JSONObject(get("$BASE/${ch.id}"))
            val arr = o.optJSONArray("sources")
            val list = ArrayList<Server>()
            if (arr != null) for (i in 0 until arr.length()) {
                val u = clean(arr.optString(i, "")); if (u.isNotEmpty()) list.add(Server("Server ${list.size + 1}", u, u.contains(".m3u8", true)))
            }
            if (list.isEmpty()) throw Exception("no live source")
            return Stream(list, o.optJSONObject("drm"))
        }
        val o = JSONObject(get("$BASE2/channels/${ch.id}"))
        val arr = o.optJSONArray("servers"); val list = ArrayList<Server>()
        if (arr != null) for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val u = clean(s.optString("playable", "")); if (u.isEmpty()) continue
            val label = clean(s.optString("label", "")).ifEmpty { "Server ${list.size + 1}" }
            val type = clean(s.optString("type", ""))
            list.add(Server(label, u, type.equals("hls", true) || looksHls(u) || !u.contains(".mpd", true)))
        }
        if (list.isEmpty()) throw Exception("no live server")
        return Stream(list, null)
    }

    private fun play(index: Int) {
        if (shown.isEmpty()) return
        current = (index + shown.size) % shown.size
        val ch = shown[current]
        if (layer.visibility != View.VISIBLE) { aurora.pause(); layer.alpha = 0f; layer.visibility = View.VISIBLE; layer.animate().alpha(1f).setDuration(250).start() }
        spinner.visibility = View.VISIBLE
        closeServers(); stopInlineEmbed(); releasePlayer(); pv.visibility = View.VISIBLE; stream = null; ui.removeCallbacks(stall)
        val my = ++token
        bindInfo(ch)
        io.execute {
            try {
                val st = resolve(ch)
                runOnUiThread { if (my == token) { stream = st; startServer(0) } }
            } catch (e: Exception) {
                runOnUiThread { if (my == token) failAll(ch, e.message ?: "unavailable") }
            }
        }
        fetchProgramme(ch, my)
    }

    private fun clearKey(d: JSONObject): DrmSessionManager {
        val lic = """{"keys":[{"kty":"oct","k":"${hexToB64Url(d.getString("key"))}","kid":"${hexToB64Url(d.getString("keyId"))}"}],"type":"temporary"}"""
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true).build(LocalMediaDrmCallback(lic.toByteArray()))
    }

    private val stall = Runnable { serverFailed("Stream is not responding") }

    /** Same server first with a second header profile (a 403 is often just the wrong UA/cookie), then the next server. */
    private fun serverFailed(reason: String) {
        if (stream != null && profile == 0) { Toast.makeText(this, "$reason – retrying", Toast.LENGTH_SHORT).show(); startServer(serverIdx, 1) }
        else if (stream != null && profile == 1 && WORKER.isNotBlank()) { Toast.makeText(this, "$reason – trying via proxy", Toast.LENGTH_SHORT).show(); startServer(serverIdx, 2) }
        else nextServer(reason)
    }

    /** Fresh tokens + native player first, TG web player only if that fails. Works from native and web mode. */
    private fun reloadStream() {
        if (current < 0) return
        if (embedSession != null) { reloadEmbed(); return }
        Toast.makeText(this, "Reloading stream…", Toast.LENGTH_SHORT).show()
        retries = 0; play(current)
    }

    private fun startServer(i: Int, prof: Int = 0) {
        val st = stream ?: return; val s = st.servers.getOrNull(i) ?: return
        serverIdx = i; profile = prof; ui.removeCallbacks(stall); releasePlayer(); spinner.visibility = View.VISIBLE
        refreshInfoCat()
        var url = s.url
        val hdrs = HashMap<String, String>()
        val agent: String
        if (prof == 2) {
            // Through the Cloudflare Worker: it adds Referer/UA itself and rewrites playlists so segments follow.
            val u = Uri.parse(s.url)
            url = "${WORKER.trimEnd('/')}/${u.scheme}/${u.encodedAuthority}${u.encodedPath ?: "/"}" + (u.encodedQuery?.let { "?$it" } ?: "")
            agent = UA; hdrs["X-Aktv-Key"] = WORKER_KEY
        } else if (prof == 0) {
            // Same request a browser makes for TG's own player: browser UA, token only in the URL.
            agent = UA; hdrs["Referer"] = "$SITE/"; hdrs["Origin"] = SITE
        } else {
            agent = "plaYtv/7.1.5 (Linux;Android 13) ExoPlayerLib/2.11.7"
            val tokenCookie = Regex("__hdnea__=([^&]+)").find(url)?.groupValues?.get(1)
            if (tokenCookie != null) hdrs["Cookie"] = "__hdnea__=$tokenCookie"
        }
        val http = DefaultHttpDataSource.Factory().setUserAgent(agent)
            .setDefaultRequestProperties(hdrs).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        // Stay further behind live and never speed up: avoids the stutter/lag on slow TV boxes.
        val item = MediaItem.Builder().setUri(url).setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(if (s.hls) 12000 else 10000)
                .setMinOffsetMs(6000).setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()
        ).build()
        val src: MediaSource = if (s.hls) {
            HlsMediaSource.Factory(http).setAllowChunklessPreparation(true).createMediaSource(item)
        } else {
            val f = DashMediaSource.Factory(http)
            val d = st.drm
            if (d != null && d.has("keyId")) { val mgr = clearKey(d); f.setDrmSessionManagerProvider { mgr } }
            f.createMediaSource(item)
        }
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        val selector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setMaxVideoSize(1280, 720).setExceedRendererCapabilitiesIfNecessary(false))
        }
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val p = ExoPlayer.Builder(this, renderers).setLoadControl(load).setTrackSelector(selector).build()
        p.setMediaSource(src); p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (p !== player) return
                if (state == Player.STATE_READY) { spinner.visibility = View.GONE; retries = 0; ui.removeCallbacks(stall) }
                if (state == Player.STATE_BUFFERING) { spinner.visibility = View.VISIBLE; ui.removeCallbacks(stall); ui.postDelayed(stall, 20000) }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (p !== player) return
                ui.removeCallbacks(stall)
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { p.seekToDefaultPosition(); p.prepare(); return }
                val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                serverFailed("Playback error: ${e.errorCodeName}${if (code != null) " (HTTP $code)" else ""}")
            }
        })
        pv.player = p; player = p; p.prepare()
        ui.postDelayed(stall, 20000)
        showInfo()
    }

    /** Auto-failover: next server → fresh tokens → built-in web player. */
    private fun nextServer(reason: String) {
        val st = stream ?: return
        if (serverIdx + 1 < st.servers.size) {
            Toast.makeText(this, "Trying ${st.servers[serverIdx + 1].label}…", Toast.LENGTH_SHORT).show()
            startServer(serverIdx + 1)
        } else if (retries < 1) {
            retries++; play(current)
        } else {
            releasePlayer(); spinner.visibility = View.GONE
            shown.getOrNull(current)?.let { openWebPlayer(it, reason) }
        }
    }
    private fun failAll(ch: Channel, reason: String) {
        if (retries < 1) { retries++; play(current) } else { spinner.visibility = View.GONE; openWebPlayer(ch, reason) }
    }
    private fun embedUrl(ch: Channel) = if (ch.v2) "$SITE/v2/embed/${ch.id}" else "$SITE/embed/${ch.id}"

    /** Render TG's own embed page inside the existing playback layer, keeping AKTV's UI around it. */
    private fun openWebPlayer(ch: Channel, reason: String?) {
        token++
        ui.removeCallbacks(stall)
        releasePlayer()
        stopInlineEmbed()
        closeServers()
        stream = null
        pv.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        layer.alpha = 1f
        layer.visibility = View.VISIBLE
        aurora.pause()
        Toast.makeText(this, if (reason != null) "$reason – trying TG player" else "Opening TG player", Toast.LENGTH_LONG).show()
        try {
            val runtime = BrowserActivity.runtime(this)
            val view = GeckoView(this)
            // TextureView composes correctly under AKTV's black layer, fades and overlays;
            // the default SurfaceView sits behind the window and shows as a black screen.
            view.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            view.setBackgroundColor(Color.BLACK)
            val session = GeckoSession(GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                .build())
            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    runOnUiThread {
                        if (embedSession === session) spinner.visibility = View.GONE
                        if (!success && embedSession === session) {
                            Toast.makeText(this@MainActivity, "TG player page failed to load", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "TG player crashed", Toast.LENGTH_LONG).show() }
                }
            }
            BrowserActivity.allowAutoplay(session)
            session.open(runtime)
            view.setSession(session)
            // Insert at the back so AKTV's native guide, info and remote overlays remain above the video.
            layer.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
            embedView = view
            embedSession = session
            embedReloads = 0
            session.loadUri(embedUrl(ch))
            scheduleEmbedWatchdog(session)
        } catch (e: Exception) {
            spinner.visibility = View.GONE
            pv.visibility = View.VISIBLE
            Toast.makeText(this, "TG player error: ${e.message ?: "unable to start"}", Toast.LENGTH_LONG).show()
        }
    }

    private var embedReloads = 0
    /** If the TG page is still not showing a picture after a while, reload it (TG then fetches a working server itself). */
    private fun scheduleEmbedWatchdog(session: GeckoSession) {
        ui.removeCallbacks(embedWatchdog)
        embedWatchdogSession = session
        ui.postDelayed(embedWatchdog, 45000)
    }
    private var embedWatchdogSession: GeckoSession? = null
    private val embedWatchdog = Runnable { embedWatchdogTick() }
    private fun embedWatchdogTick() {
        val s = embedSession
        if (s != null && s === embedWatchdogSession && embedReloads < 3) {
            embedReloads++
            Toast.makeText(this, "TG player slow – reloading ($embedReloads/3)", Toast.LENGTH_SHORT).show()
            spinner.visibility = View.VISIBLE
            s.reload()
            ui.postDelayed(embedWatchdog, 45000)
        }
    }
    private fun reloadEmbed() {
        val s = embedSession ?: return
        embedReloads = 0
        Toast.makeText(this, "Reloading TG player…", Toast.LENGTH_SHORT).show()
        spinner.visibility = View.VISIBLE
        s.reload()
        scheduleEmbedWatchdog(s)
    }

    private fun stopInlineEmbed() {
        ui.removeCallbacks(embedWatchdog)
        val session = embedSession
        embedSession = null
        try { session?.close() } catch (_: Exception) { }
        val view = embedView
        embedView = null
        if (view != null) layer.removeView(view)
        spinner.visibility = View.GONE
    }

    private fun releasePlayer() { pv.player = null; player?.release(); player = null }
    private fun closePlayer() {
        token++; ui.removeCallbacks(stall); stopInlineEmbed(); pv.visibility = View.VISIBLE; releasePlayer(); stream = null; retries = 0
        ui.removeCallbacks(hideInfo); aurora.resume(); resetServers(); resetGuide()
        if (layer.visibility == View.VISIBLE) layer.animate().alpha(0f).setDuration(200).withEndAction { layer.visibility = View.GONE }.start()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (splash.visibility == View.VISIBLE) { if (e.action == KeyEvent.ACTION_DOWN) splash.skip(); return true }
        if (searchShown) return searchKey(e) || super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_SEARCH) { openSearch(); return true }
        if (layer.visibility == View.VISIBLE && serverShown) return serverKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && guideShown) return guideKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closePlayer(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { openGuide(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { retries = 0; play(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { retries = 0; play(current + 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> { openServers(); return true }
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_REWIND -> { reloadStream(); return true }
        }
        // Going up out of the rows always lands on the current tab, never switches tabs by accident.
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, f, View.FOCUS_UP)
                if (next != null && segs.any { it === next }) { segs[tab].requestFocus(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }
    override fun onStart() { super.onStart(); if (layer.visibility != View.VISIBLE) aurora.resume() }
    override fun onStop() {
        super.onStop(); token++; ui.removeCallbacks(stall); stopInlineEmbed(); pv.visibility = View.VISIBLE; releasePlayer(); layer.visibility = View.GONE; aurora.pause()
        resetServers(); resetGuide()
        if (searchShown) { searchShown = false; searchView.visibility = View.GONE; home.visibility = View.VISIBLE; restoreHome() }
    }

    // ---------- home focus ownership ----------
    private fun blockHome() {
        if (home.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            lastHomeFocus = currentFocus; home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }
    private fun restoreHome() {
        if (home.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            home.descendantFocusability = homeFocusMode
            val back = lastHomeFocus; lastHomeFocus = null
            if (back?.takeIf { it.isAttachedToWindow }?.requestFocus() != true) rowsView.requestFocus()
        }
    }

    // ---------- servers ----------
    private val serverTimeout = Runnable { closeServers() }
    private fun openServers() {
        if (stream == null && embedSession == null) { showInfo(); return }
        serverRows = listOf("Reload stream") + (stream?.servers?.map { it.label } ?: emptyList()) + "Web player"
        blockHome(); serverShown = true
        serverPanel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        serverAdapter.notifyDataSetChanged()
        serverPanel.animate().cancel()
        serverPanel.visibility = View.VISIBLE; serverPanel.alpha = 0f; serverPanel.translationX = -40 * dp
        serverPanel.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(Ease).start()
        showInfo()
        serverList.post { serverList.findViewHolderForAdapterPosition(if (stream != null) serverIdx + 1 else 0)?.itemView?.requestFocus() ?: serverList.requestFocus() }
        ui.removeCallbacks(serverTimeout); ui.postDelayed(serverTimeout, 10000)
    }
    private fun closeServers() {
        if (!serverShown) return
        serverShown = false; ui.removeCallbacks(serverTimeout)
        serverPanel.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        currentFocus?.clearFocus()
        serverPanel.animate().alpha(0f).translationX(-40 * dp).setDuration(180)
            .withEndAction { if (!serverShown) serverPanel.visibility = View.GONE }.start()
    }
    private fun resetServers() {
        serverShown = false; ui.removeCallbacks(serverTimeout); serverPanel.animate().cancel()
        serverPanel.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; serverPanel.visibility = View.GONE
    }
    private fun serverKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        ui.removeCallbacks(serverTimeout); ui.postDelayed(serverTimeout, 10000)
        val f = currentFocus
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> { closeServers(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (f == null || !within(f, serverList)) { serverList.findViewHolderForAdapterPosition(if (stream != null) serverIdx + 1 else 0)?.itemView?.requestFocus(); return true }
                val next = f.focusSearch(if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                return next == null || !within(next, serverList)
            }
        }
        return false
    }
    private val serverAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = serverRows.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_server, p, false)
            v.background = GlassDrawable(dp, 14f).apply { idleVisible = false }
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val glass = v.background as GlassDrawable
            val name = v.findViewById<TextView>(R.id.srvName); val state = v.findViewById<TextView>(R.id.srvState)
            name.text = serverRows[i]
            val n = stream?.servers?.size ?: 0
            val reload = i == 0; val web = i > n
            val active = !reload && !web && i - 1 == serverIdx
            state.text = if (active) "Active" else ""
            glass.accent = uiAccent; glass.snap(v.isFocused); glass.idleVisible = active
            name.setTextColor(if (v.isFocused || active) Accent.TEXT else Accent.DIM)
            v.setOnFocusChangeListener { _, f -> glass.accent = uiAccent; glass.focused = f; name.setTextColor(if (f || active) Accent.TEXT else Accent.DIM) }
            v.setOnClickListener {
                if (reload) { closeServers(); reloadStream() }
                else if (web) { shown.getOrNull(current)?.let { openWebPlayer(it, null) } }
                else { closeServers(); retries = 0; startServer(i - 1) }
            }
        }
    }

    // ---------- search ----------
    private fun kbKey(label: String, weight: Float, action: () -> Unit): TextView = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 15f; typeface = fSemi; setTextColor(Accent.DIM)
        isFocusable = true; isClickable = true
        val g = GlassDrawable(dp, 12f); background = g
        val m = (3 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(0, (44 * dp).toInt(), weight).apply { setMargins(m, m, m, m) }
        setOnFocusChangeListener { _, f -> g.accent = kbAccent; g.focused = f; setTextColor(if (f) Accent.TEXT else Accent.DIM) }
        setOnClickListener { action() }
    }
    private fun buildKeyboard() {
        val keys = ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() }
        kbd.removeAllViews()
        for (r in 0 until 6) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 6) { val k = keys[r * 6 + c]; row.addView(kbKey(k, 1f) { typeText(k) }) }
            kbd.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        val last = LinearLayout(this); last.orientation = LinearLayout.HORIZONTAL
        last.addView(kbKey("Space", 2f) { typeText(" ") }); last.addView(kbKey("Delete", 2f) { backspace() }); last.addView(kbKey("Clear", 2f) { searchQ.setLength(0); updateSearch() })
        kbd.addView(last, LinearLayout.LayoutParams(-1, -2))
    }
    private fun typeText(s: String) { if (searchQ.length < 28) { searchQ.append(s); updateSearch() } }
    private fun backspace() { if (searchQ.isNotEmpty()) { searchQ.deleteCharAt(searchQ.length - 1); updateSearch() } }

    private fun updateSearch() {
        val q = searchQ.toString().trim()
        searchQuery.text = if (searchQ.isEmpty()) "Type a channel name…" else searchQ.toString()
        searchQuery.setTextColor(if (searchQ.isEmpty()) Accent.FAINT else Accent.TEXT)
        val pool = all + allV2
        val toks = q.lowercase().split(' ').filter { it.isNotBlank() }
        val res = if (toks.isEmpty()) pool else pool.filter { c ->
            val h = (c.name + " " + c.category).lowercase(); toks.all { h.contains(it) }
        }.sortedBy { if (it.name.lowercase().startsWith(toks[0])) 0 else 1 }
        searchResults = res.take(300)
        searchHeader.text = when {
            toks.isEmpty() -> "All channels  ${pool.size}" + if (!v2Loaded) "   ·   loading 24/7…" else ""
            res.isEmpty() -> "No channels match “$q”"
            else -> "${res.size} ${if (res.size == 1) "result" else "results"}"
        }
        srAdapter.notifyDataSetChanged(); searchList.scrollToPosition(0)
    }
    private fun openSearch() {
        if (searchShown) return
        if (guideShown) closeGuide()
        closeServers()
        blockHome(); searchShown = true
        if (layer.visibility != View.VISIBLE) { kbAccent = Accent.SIGNAL; searchView.setBackgroundColor(0xFF060812.toInt()); home.visibility = View.INVISIBLE }
        else searchView.setBackgroundColor(0xF4060812.toInt())
        searchQ.setLength(0)
        searchView.animate().cancel()
        searchView.visibility = View.VISIBLE; searchView.alpha = 0f
        searchView.animate().alpha(1f).setDuration(220).setInterpolator(Ease).start()
        searchView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        updateSearch()
        if (!v2Loaded) loadV2()
        (kbd.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
    }
    private fun closeSearch() {
        if (!searchShown) return
        searchShown = false
        searchView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        currentFocus?.clearFocus()
        searchView.animate().cancel()
        home.visibility = View.VISIBLE
        searchView.animate().alpha(0f).setDuration(180).withEndAction { if (!searchShown) searchView.visibility = View.GONE }.start()
        if (layer.visibility != View.VISIBLE) restoreHome()
    }
    private fun searchKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closeSearch(); return true }
            KeyEvent.KEYCODE_DEL -> { backspace(); return true }
            KeyEvent.KEYCODE_SPACE -> { typeText(" "); return true }
        }
        if ((e.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z || e.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) && !e.isCtrlPressed) {
            val u = e.unicodeChar
            if (u > 0) { typeText(u.toChar().uppercaseChar().toString()); return true }
        }
        return false
    }
    private fun playFromSearch(i: Int) {
        val list = searchResults
        if (i !in list.indices) return
        shown = list; shownCat = "Search"; retries = 0
        play(i); closeSearch()
    }
    private val srAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = searchResults.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_ch, p, false)
            v.background = GlassDrawable(dp, 14f).apply { idleVisible = false }
            v.findViewById<View>(R.id.chLogoBox).background = GlassDrawable(dp, 12f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val ch = searchResults[i]; val glass = v.background as GlassDrawable
            val name = v.findViewById<TextView>(R.id.chName)
            name.text = ch.name
            v.findViewById<TextView>(R.id.chNum).text = ""
            bindLogo(v.findViewById(R.id.chLogo), null, ch)
            val now = v.findViewById<TextView>(R.id.chNow)
            now.text = if (ch.v2) "${ch.category}  ·  24/7" else ch.category; now.setTextColor(Accent.FAINT); now.visibility = View.VISIBLE
            v.findViewById<View>(R.id.chFav).visibility = if (ch.key in favs) View.VISIBLE else View.GONE
            glass.accent = accentOf(ch.key); glass.snap(v.isFocused)
            name.setTextColor(if (v.isFocused) Accent.TEXT else Accent.DIM)
            v.setOnFocusChangeListener { _, f ->
                glass.accent = accentOf(ch.key); glass.focused = f; name.setTextColor(if (f) Accent.TEXT else Accent.DIM)
                if (f) ensureAccent(ch) { c -> if (v.isFocused) glass.accent = c }
            }
            v.setOnClickListener { playFromSearch(i) }
            v.setOnLongClickListener { toggleFav(ch); notifyItemChanged(i); true }
        }
    }

    // ---------- channel guide (Right opens · Left goes back · OK plays) ----------
    private fun channelsIn(cat: String) = if (cat == FAV) playingCatalog().filter { it.key in favs } else playingCatalog().filter { it.category == cat }
    private fun within(v: View?, rv: View) = v != null && (v === rv || isInside(v, rv))
    private fun itemOf(v: View, rv: RecyclerView): View { var x = v; while (x.parent !== rv) x = x.parent as View; return x }
    private val guideTimeout = Runnable { closeGuide() }
    private fun bumpGuide() { ui.removeCallbacks(guideTimeout); ui.postDelayed(guideTimeout, 15000) }

    private fun focusRow(rv: RecyclerView, i: Int) {
        val h = rv.findViewHolderForAdapterPosition(i)
        if (h != null && h.itemView.isShown) { h.itemView.requestFocus(); return }
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, (120 * dp).toInt())
        rv.post { rv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: rv.requestFocus() }
    }

    private fun openGuide() {
        val cat0 = playingCatalog()
        if (cat0.isEmpty()) return
        val byCat = cat0.groupBy { it.category }
        val favCount = cat0.count { it.key in favs }
        guideCats = (if (favCount > 0) listOf(FAV) else emptyList()) + byCat.keys.sorted()
        catCounts = byCat.mapValues { it.value.size } + (FAV to favCount)
        blockHome()
        closeServers()
        guideShown = true; guideCat = ""
        guide.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        catAdapter.notifyDataSetChanged()
        ui.removeCallbacks(hideInfo); hideInfo.run()
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.visibility = View.VISIBLE; guide.alpha = 0f; guide.translationX = 60 * dp
        guide.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(Ease).start()
        guideScrim.visibility = View.VISIBLE; guideScrim.alpha = 0f
        guideScrim.animate().alpha(1f).setDuration(260).start()
        focusRow(catList, guideCats.indexOf(shownCat).coerceAtLeast(0))
        bumpGuide()
    }

    private fun openCategory(i: Int) {
        val cat = guideCats.getOrNull(i) ?: return
        val prev = guideCats.indexOf(guideCat)
        guideCat = cat; categoryChannels = channelsIn(cat)
        guideChannels = categoryChannels
        chHeader.text = "$cat  ${guideChannels.size}"
        chAdapter.notifyDataSetChanged()
        if (chCol.visibility != View.VISIBLE) {
            chCol.animate().cancel()
            chCol.visibility = View.VISIBLE; guideDivider.visibility = View.VISIBLE
            chCol.alpha = 0f; chCol.translationX = 30 * dp
            chCol.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(Ease).start()
        }
        val playing = if (cat == shownCat) guideChannels.indexOfFirst { it.key == shown.getOrNull(current)?.key } else -1
        chList.scrollToPosition(0)
        focusRow(chList, playing.coerceAtLeast(0))
        if (prev >= 0) catAdapter.notifyItemChanged(prev)
        catAdapter.notifyItemChanged(i)
    }

    private fun backToCats() {
        val i = guideCats.indexOf(guideCat).coerceAtLeast(0)
        guideCat = ""
        focusRow(catList, i)
        catAdapter.notifyItemChanged(i)
        chCol.animate().cancel()
        chCol.animate().alpha(0f).translationX(30 * dp).setDuration(180)
            .withEndAction { if (guideCat.isEmpty()) { chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
    }

    private fun closeGuide() {
        if (!guideShown) return
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        currentFocus?.clearFocus()
        guide.animate().alpha(0f).translationX(60 * dp).setDuration(200)
            .withEndAction { if (!guideShown) { guide.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
        guideScrim.animate().alpha(0f).setDuration(200).withEndAction { if (!guideShown) guideScrim.visibility = View.GONE }.start()
    }

    /** Hard reset when the player itself closes: hide the guide and give focus back to the home screen. */
    private fun resetGuide() {
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        guide.visibility = View.GONE; guideScrim.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        if (!searchShown) restoreHome()
    }

    private fun playFromGuide(i: Int) {
        val list = guideChannels; val cat = guideCat
        if (i !in list.indices) return
        shown = list; shownCat = cat; retries = 0
        closeGuide(); play(i)
    }

    private fun guideKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        bumpGuide()
        val f = currentFocus
        val inCh = within(f, chList); val inCat = within(f, catList); val inSearch = f === chSearch
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (inCh || inSearch) backToCats() else closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (inCat && f != null && f !== catList) openCategory(catList.getChildAdapterPosition(itemOf(f, catList)))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!inCh && !inCat && !inSearch) { focusRow(catList, 0); return true }
                if (inSearch) { if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) focusRow(chList, 0); return true }
                val list = if (inCh) chList else catList
                // stay inside the current column (the search box sits above the channel list)
                val next = f!!.focusSearch(if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                return next == null || !(within(next, list) || (inCh && next === chSearch))
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> return true
        }
        return false   // OK / long-press go to the focused row as normal
    }

    private val catAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideCats.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_cat, p, false)
            v.background = GlassDrawable(dp, 14f).apply { idleVisible = false }
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val cat = guideCats[i]; val glass = v.background as GlassDrawable
            val name = v.findViewById<TextView>(R.id.catName)
            name.text = cat
            v.findViewById<TextView>(R.id.catCount).text = (catCounts[cat] ?: 0).toString()
            v.findViewById<View>(R.id.catMark).visibility = if (cat == shownCat) View.VISIBLE else View.INVISIBLE
            val open = cat == guideCat
            glass.accent = uiAccent; glass.idleVisible = open; glass.snap(v.isFocused)
            name.setTextColor(if (v.isFocused || open) Accent.TEXT else Accent.DIM)
            v.setOnFocusChangeListener { _, f -> glass.accent = uiAccent; glass.focused = f; name.setTextColor(if (f || open) Accent.TEXT else Accent.DIM) }
            v.setOnClickListener { openCategory(i) }
        }
    }

    private val chAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideChannels.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_ch, p, false)
            v.background = GlassDrawable(dp, 14f).apply { idleVisible = false }
            v.findViewById<View>(R.id.chLogoBox).background = GlassDrawable(dp, 12f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val ch = guideChannels[i]; val glass = v.background as GlassDrawable
            val name = v.findViewById<TextView>(R.id.chName)
            name.text = ch.name
            v.findViewById<TextView>(R.id.chNum).text = (i + 1).toString()
            bindLogo(v.findViewById(R.id.chLogo), null, ch)
            val now = v.findViewById<TextView>(R.id.chNow)
            now.text = "Now playing"; now.setTextColor(Accent.LIVE)
            val playing = shown.getOrNull(current)?.key == ch.key
            now.visibility = if (playing) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.chFav).visibility = if (ch.key in favs) View.VISIBLE else View.GONE
            glass.accent = accentOf(ch.key); glass.idleVisible = playing; glass.snap(v.isFocused)
            name.setTextColor(if (v.isFocused || playing) Accent.TEXT else Accent.DIM)
            v.setOnFocusChangeListener { _, f ->
                glass.accent = accentOf(ch.key); glass.focused = f; name.setTextColor(if (f || playing) Accent.TEXT else Accent.DIM)
                if (f) ensureAccent(ch) { c -> if (v.isFocused) glass.accent = c }
            }
            v.setOnClickListener { playFromGuide(i) }
            v.setOnLongClickListener { toggleFav(ch); notifyItemChanged(i); true }
        }
    }

    // ---------- adapters ----------
    private inner class TileAdapter(val row: Row) : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = row.tiles.size
        override fun getItemViewType(i: Int) = when (row.tiles[i]) { is Tile.Ch -> 0; is Tile.St -> 1; else -> 2 }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val l = when (t) { 0 -> R.layout.item_tile; 1 -> R.layout.item_site_tile; else -> R.layout.item_hint }
            val v = LayoutInflater.from(p.context).inflate(l, p, false)
            if (t != 1) v.background = GlassDrawable(dp, 20f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
            val t = row.tiles[i]
            if (payloads.isNotEmpty() && t is Tile.Ch) { h.itemView.findViewById<View>(R.id.fav).visibility = if (t.c.key in favs) View.VISIBLE else View.GONE; return }
            super.onBindViewHolder(h, i, payloads)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            when (val t = row.tiles[i]) {
                is Tile.Ch -> {
                    val glass = v.background as GlassDrawable
                    v.tag = t.c.key; glass.accent = accentOf(t.c.key); glass.snap(v.isFocused)
                    v.scaleX = if (v.isFocused) 1.08f else 1f; v.scaleY = v.scaleX
                    v.findViewById<TextView>(R.id.name).text = t.c.name
                    val logo = v.findViewById<ImageView>(R.id.logo); val mono = v.findViewById<TextView>(R.id.mono)
                    if (t.c.logo.isBlank()) { logo.setImageDrawable(null); logo.visibility = View.INVISIBLE; mono.visibility = View.VISIBLE; mono.text = initials(t.c.name) }
                    else {
                        mono.visibility = View.GONE; logo.visibility = View.VISIBLE
                        logo.load(t.c.logo) {
                            allowHardware(false)
                            listener(onSuccess = { _, r ->
                                extractAccent(t.c.key, r.drawable) { c ->
                                    if (v.tag == t.c.key) { glass.accent = c; if (v.isFocused) heroGlowD.animateTo(c) }
                                }
                            })
                        }
                    }
                    v.findViewById<View>(R.id.fav).visibility = if (t.c.key in favs) View.VISIBLE else View.GONE
                    v.setOnFocusChangeListener { x, f ->
                        val c = accentOf(t.c.key); glass.accent = c; glass.focused = f
                        focusAnim(x, f); if (f) setHero(t)
                    }
                    v.setOnClickListener {
                        shown = row.tiles.filterIsInstance<Tile.Ch>().map { it.c }; shownCat = row.title
                        retries = 0; play(shown.indexOfFirst { it.key == t.c.key })
                    }
                    v.setOnLongClickListener { toggleFav(t.c); true }
                }
                is Tile.St -> {
                    val grad = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.s.c1, t.s.c2)).apply { cornerRadius = 24 * dp }
                    val glass = GlassDrawable(dp, 24f).apply { accent = t.s.c1; showFill = false }
                    v.background = LayerDrawable(arrayOf(grad, glass)); v.clipToOutline = true
                    v.findViewById<TextView>(R.id.siteMono).text = t.s.name
                    v.findViewById<TextView>(R.id.siteName).text = t.s.name
                    v.findViewById<TextView>(R.id.siteUrl).text = t.s.url.removePrefix("https://")
                    v.setOnFocusChangeListener { x, f -> glass.focused = f; focusAnim(x, f); if (f) setHero(t) }
                    v.setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java).putExtra("url", t.s.url)) }
                }
                else -> {}
            }
        }
    }

    private val rowAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            val row = rows[i]
            v.findViewById<TextView>(R.id.rowTitle).text = row.title
            val n = row.tiles.count { it is Tile.Ch }
            v.findViewById<TextView>(R.id.rowCount).text = if (n > 0) n.toString() else ""
            val rv = v.findViewById<RecyclerView>(R.id.rowList)
            if (rv.layoutManager == null) {
                rv.layoutManager = SnapLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL)
                rv.itemAnimator = null
                rv.overScrollMode = View.OVER_SCROLL_NEVER
                rv.setHasFixedSize(true)
                rv.isNestedScrollingEnabled = false
                rv.setRecycledViewPool(tilePool)
                rv.setItemViewCacheSize(4)
            }
            val ad = TileAdapter(row); inner[i] = ad; rv.adapter = ad
            v.animate().cancel(); v.translationY = 0f
            v.alpha = if (rowsView.hasFocus()) 0.45f else 1f
        }
    }
}
