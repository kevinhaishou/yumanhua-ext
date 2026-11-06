package eu.kanade.tachiyomi.extension.zh.yumanhua

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * 漫画客 (yumanhua.com)
 *
 * 正文解密链（逆向自 /static/js/all2.js，已在真实页面端到端验证）：
 *   1. 章节页内嵌 Dean Edwards packer，解包后得到  var xxx="<密文>"
 *   2. base64 decode 密文
 *   3. 与 KEY_TABLE[readerContainer 的 data-id] 逐字节 XOR（data-id 每次请求随机）
 *   4. 再次 base64 decode -> JSON 数组 = 带签名的图片 URL
 */
class YuManHua : ParsedHttpSource() {

    override val name = "漫画客"
    override val baseUrl = "http://yumanhua.com"
    override val lang = "zh"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder().build()

    private fun headers() = okhttp3.Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", baseUrl)
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    // ============================== 列表 ==============================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/rank/1", headers())

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/rank/5", headers())

    // rank / sort 页共用同一列表结构
    override fun popularMangaSelector() = LIST_SELECTOR
    override fun latestUpdatesSelector() = LIST_SELECTOR
    override fun searchMangaSelector() = LIST_SELECTOR

    override fun popularMangaFromElement(element: org.jsoup.nodes.Element) = listFromElement(element)
    override fun latestUpdatesFromElement(element: org.jsoup.nodes.Element) = listFromElement(element)
    override fun searchMangaFromElement(element: org.jsoup.nodes.Element) = listFromElement(element)

    override fun popularMangaNextPage() = false
    override fun latestUpdatesNextPage() = false
    override fun searchMangaNextPage() = false

    private fun listFromElement(element: org.jsoup.nodes.Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.select("a").first()!!.attr("href"))
        title = element.select("h2").text()
        thumbnail_url = element.select("img.cartoon-poster").attr("abs:data-src")
        description = element.select("p.cartoon-introduction").text()
    }

    // ============================== 详情 ==============================

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.select("h1.name").text()
        author = document.selectFirst("meta[property=og:author]")?.attr("content")
        thumbnail_url = document.selectFirst(".book-cover .thumbnail img")?.attr("abs:data-src")
        description = document.selectFirst(".cartoon-introduction")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        // .comic-tags 文本形如 "热血 奇幻 更新：11月01日 作者：xxx 最新：第421话"
        val tags = document.selectFirst(".comic-tags")?.text() ?: ""
        genre = tags.substringBefore("更新：").trim()
        status = if (tags.contains("完结")) SManga.COMPLETED else SManga.ONGOING
    }

    // ============================== 章节 ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val bookId = response.request.url.pathSegments.dropLast(1).last()

        // 详情页只给最新 20 话；首条 a 是"开始阅读"入口，要跳过
        val chapters = doc.select(".chaplist-box a")
            .filter { !it.text().contains("开始阅读") }
            .map { a ->
                SChapter.create().apply {
                    setUrlWithoutDomain(a.attr("href"))
                    name = a.text().trim()
                }
            }.toMutableList()

        // POST /morechapter {id} 补齐剩余全量章节，顺序同为 新 -> 旧
        val body = FormBody.Builder().add("id", bookId).build()
        client.newCall(POST("$baseUrl/morechapter", headers(), body)).execute().use { resp ->
            val arr = JSONObject(resp.body!!.string()).optJSONArray("data") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                chapters.add(
                    SChapter.create().apply {
                        url = "/$bookId/${o.getString("chapterid")}.html"
                        name = o.getString("chaptername")
                    },
                )
            }
        }
        return chapters
    }

    override fun chapterListSelector() = throw UnsupportedOperationException()
    override fun chapterFromElement(element: org.jsoup.nodes.Element) = throw UnsupportedOperationException()

    // ============================== 正文图片 ==============================

    override fun pageListParse(document: Document): List<Page> {
        val html = document.outerHtml()
        val cipher = extractCipher(html)
            ?: throw Exception("未找到图片数据，站点可能已改版")
        // data-id 每次请求随机，用来在 key 表里选 key
        val dataId = DATA_ID_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val key = String(Base64.decode(KEY_TABLE.getOrElse(dataId) { KEY_TABLE[0] }, Base64.DEFAULT))

        val step1 = Base64.decode(cipher, Base64.DEFAULT)
        val step2 = ByteArray(step1.size) { i ->
            ((step1[i].toInt() and 0xFF) xor key[i % key.length].toInt()).toByte()
        }
        val step3 = String(
            Base64.decode(String(step2, Charsets.ISO_8859_1), Base64.DEFAULT),
            Charsets.UTF_8,
        )
        val urls = JSONArray(step3)
        return (0 until urls.length()).map { Page(it, "", urls.getString(it)) }
    }

    /** 密文可能明文内联，也可能藏在 Dean Edwards packer 中 */
    private fun extractCipher(html: String): String? {
        PLAIN_REGEX.find(html)?.let { return unescapeSlash(it.groupValues[1]) }

        val m = PACKER_REGEX.find(html) ?: return null
        val unpacked = unpackPacker(
            m.groupValues[1],
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
            m.groupValues[4].split('|'),
        )
        return PLAIN_REGEX.find(unpacked)?.groupValues?.get(1)?.let { unescapeSlash(it) }
    }

    private fun unescapeSlash(s: String) = s.replace("\\/", "/")

    /** packer 解包：token 按 base-a 编码回查 keys 数组 */
    private fun unpackPacker(payload: String, a: Int, c: Int, keys: List<String>): String {
        var out = payload
        for (i in c - 1 downTo 0) {
            val raw = keys.getOrNull(i) ?: continue
            if (raw.isEmpty()) continue
            val token = encodeToken(i, a)
            if (token.isEmpty()) continue
            out = out.replace(Regex("\\b" + Regex.escape(token) + "\\b")) { raw }
        }
        return out
    }

    private fun encodeToken(n: Int, a: Int): String {
        val digits = ArrayList<Int>()
        var x = n
        while (x >= a) {
            digits.add(x % a)
            x /= a
        }
        digits.add(x)
        val sb = StringBuilder()
        for (d in digits.asReversed()) {
            sb.append(if (d > 35) (d + 29).toChar() else d.toString(36))
        }
        return sb.toString()
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== 搜索 ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val body = FormBody.Builder().add("k", query).build()
            return POST("$baseUrl/s", headers(), body)
        }
        val cat = filters.filterIsInstance<CategoryFilter>().firstOrNull()
            ?.toUriPart() ?: "1"
        return GET("$baseUrl/sort/$cat", headers())
    }

    /** /s 返回 JSON，/sort/{n} 返回 HTML，按请求路径分流 */
    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath != "/s") {
            return super.searchMangaParse(response)
        }
        val arr = JSONObject(response.body!!.string()).getJSONArray("data")
        val mangas = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SManga.create().apply {
                url = "/${o.getString("id")}/"
                title = o.getString("name")
                thumbnail_url = o.optString("imgurl")
                status = SManga.UNKNOWN
            }
        }
        return MangasPage(mangas, false)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("分类浏览（搜索框留空时生效）"),
        CategoryFilter(CATEGORIES),
    )

    private class CategoryFilter(vals: Array<Pair<String, String>>) :
        Filter.Select<String>("分类", vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val LIST_SELECTOR = ".rank-list li"

        private val PLAIN_REGEX = Regex("""var\s+\w+\s*=\s*"([A-Za-z0-9+/=]{500,})"""")
        private val PACKER_REGEX = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)""",
        )
        private val DATA_ID_REGEX = Regex("""readerContainer"[^>]*data-id="(\d+)"""")

        /** readerContainer data-id 0..9 -> key（base64），顺序经运行时全量提取 */
        private val KEY_TABLE = arrayOf(
            "c21raHkyNTg=",
            "c21rZDk1ZnY=",
            "bWQ0OTY5NTI=",
            "Y2Rjc2R3cQ==",
            "dmJmc2EyNTY=",
            "Y2F3ZjE1MWM=",
            "Y2Q1NmN2ZGE=",
            "OGtpaG50OQ==",
            "ZHNvMTV0bG8=",
            "NWtvNnBsaHk=",
        )

        private val CATEGORIES = arrayOf(
            Pair("冒险", "1"),
            Pair("热血", "2"),
            Pair("都市", "3"),
            Pair("玄幻", "4"),
            Pair("悬疑", "5"),
            Pair("耽美", "6"),
            Pair("恋爱", "7"),
            Pair("生活", "8"),
            Pair("搞笑", "9"),
            Pair("穿越", "10"),
            Pair("修真", "11"),
            Pair("后宫", "12"),
            Pair("大女主", "13"),
            Pair("古风", "14"),
            Pair("连载", "15"),
            Pair("完结", "16"),
        )
    }
}
