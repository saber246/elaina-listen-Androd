package com.elainalisten.article

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

internal object ArticleExtractor {
    const val maxTextLength = 60_000
    private val whitespace = Regex("[\\s\\u00a0\\u3000]+")
    private val noise = Regex(
        "(?:^|[-_\\s])(?:ads?|advert(?:isement|ising)?|sponsor(?:ed)?|sidebar|cookie|comments?|" +
            "related|recommend(?:ation)?s?|social|share|breadcrumb|pagination|newsletter|toolbar)(?:$|[-_\\s])",
        RegexOption.IGNORE_CASE,
    )
    private val contentHint = Regex(
        "(?:^|[-_\\s])(?:article|content|chapter|novel|story|readtext)(?:$|[-_\\s])",
        RegexOption.IGNORE_CASE,
    )
    private val heading = Regex(
        "^(?:第[0-9０-９零〇一二三四五六七八九十百千万两]+[章节卷回集部]|序章|楔子|尾声|后记|番外|chapter\\s+\\d+)",
        RegexOption.IGNORE_CASE,
    )
    private val navigation = Regex(
        "^(?:(?:上一|下一)[页章节回]|返回目录|目录|加入书签|点击阅读|展开全文)(?:\\s+(?:(?:上一|下一)[页章节回]|返回目录|目录))*$",
    )
    private val headings = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val blocks = setOf("article", "main", "section", "div", "p", "li", "blockquote", "pre", "figure", "figcaption")

    fun extract(page: PageSnapshot, fallbackTitle: String): ReadableArticle? {
        val document = Jsoup.parse(page.html, page.url)
        val body = document.body()
        body.select(
            "script,style,noscript,template,nav,aside,footer,form,select,button,iframe,svg,canvas," +
                "[hidden],[aria-hidden=true],[role=navigation],[role=complementary],[role=banner]",
        ).remove()
        body.select("[class],[id],[style]").filter { element ->
            noise.containsMatchIn(element.id() + " " + element.className()) ||
                element.attr("style").replace(" ", "").contains("display:none", ignoreCase = true)
        }.forEach(Element::remove)

        val root = selectContent(body) ?: return null
        val title = root.selectFirst("h1")?.text()?.let(::clean)?.takeIf(String::isNotEmpty)
            ?: clean(page.title).ifEmpty { fallbackTitle }
        val chapters = chapters(title, readBlocks(root))
        val total = chapters.sumOf { chapter -> chapter.paragraphs.sumOf(String::length) }
        return ReadableArticle(title, page.url, chapters).takeIf { total in 20..maxTextLength }
    }

    fun fromText(text: String, title: String, sourceUrl: String? = null): ReadableArticle? {
        if (text.length > maxTextLength) return null
        val lines = text.lineSequence().map(::clean).filter(String::isNotEmpty).map {
            Block(it, isHeading(it))
        }.toList()
        val chapters = chapters(title, lines)
        return ReadableArticle(title, sourceUrl, chapters).takeIf { chapters.isNotEmpty() }
    }

    private fun selectContent(body: Element): Element? {
        val scores = linkedMapOf(body to 0.0)
        body.select("article,main,[role=main],[itemprop=articleBody],[class],[id]").forEach { element ->
            val bonus = when {
                element.normalName() == "article" || element.attr("itemprop") == "articleBody" -> 140.0
                element.normalName() == "main" || element.attr("role") == "main" -> 70.0
                contentHint.containsMatchIn(element.id() + " " + element.className()) -> 85.0
                else -> 0.0
            }
            if (bonus > 0) scores[element] = bonus
        }
        body.select("p,blockquote,pre,div").forEach { block ->
            val isDiv = block.normalName() == "div"
            if (isDiv && block.children().any { it.normalName() in blocks }) return@forEach
            val prose = if (isDiv) block.ownText() else block.text()
            if (prose.length < 12 || navigation.matches(prose) || linkDensity(block, prose.length) > 0.65) {
                return@forEach
            }
            val score = 4.0 + prose.length.coerceAtMost(250) / 16.0 +
                prose.count { it in "。；，！？,.!?;" }.coerceAtMost(12)
            var ancestor = if (isDiv) block else block.parent()
            repeat(4) { depth ->
                val parent = ancestor ?: return@repeat
                scores[parent] = (scores[parent] ?: 0.0) + score / (depth + 1)
                ancestor = if (parent === body) null else parent.parent()
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(24).mapNotNull { (element, score) ->
            val textLength = element.text().length
            if (textLength < 20) return@mapNotNull null
            val density = linkDensity(element, textLength)
            if (density > 0.60) null else element to score * (1 - density) * (1 - density)
        }.maxByOrNull { it.second }?.first
    }

    private fun linkDensity(element: Element, length: Int): Double =
        element.select("a").sumOf { it.text().length }.toDouble() / length.coerceAtLeast(1)

    private fun readBlocks(root: Element): List<Block> = buildList {
        val line = StringBuilder()
        var inHeading = false
        fun flush() {
            val text = clean(line.toString())
            line.setLength(0)
            if (text.isNotEmpty() && !navigation.matches(text)) add(Block(text, inHeading || isHeading(text)))
        }
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is Element -> {
                        val tag = node.normalName()
                        if (tag in headings) {
                            flush()
                            inHeading = true
                        } else if (tag in blocks || tag == "br" || tag == "hr") {
                            flush()
                        }
                    }
                    is TextNode -> line.append(node.wholeText)
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node !is Element) return
                val tag = node.normalName()
                if (tag in headings || tag in blocks) flush()
                if (tag in headings) inHeading = false
            }
        }, root)
        flush()
    }

    private fun chapters(title: String, blocks: List<Block>): List<Chapter> = buildList {
        var chapterTitle = title
        var paragraphs = mutableListOf<String>()
        for (block in blocks) {
            if (block.heading) {
                if (paragraphs.isNotEmpty()) {
                    add(Chapter(chapterTitle, paragraphs))
                    paragraphs = mutableListOf()
                }
                chapterTitle = block.text
            } else {
                paragraphs += block.text
            }
        }
        if (paragraphs.isNotEmpty()) add(Chapter(chapterTitle, paragraphs))
    }

    private fun isHeading(text: String): Boolean = text.length <= 50 && heading.containsMatchIn(text)

    private fun clean(text: String): String = text.replace(whitespace, " ").trim()

    private data class Block(val text: String, val heading: Boolean)
}
