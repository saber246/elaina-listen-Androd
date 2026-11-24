package com.elainalisten.browser

internal object PageControls {
    private val find = """
        function pageControls() {
            const pager = '[class*="pagination"],[class*="pager"],[id*="pagination"],[aria-label*="分页"]';
            const visible = element => {
                const style = getComputedStyle(element);
                return !element.closest('[hidden],[aria-hidden="true"],[inert]') &&
                    element.getClientRects().length > 0 && style.visibility === 'visible';
            };
            const label = text => (text || '').normalize('NFKC').trim()
                .replace(/[\s»›>→▶]+$/, '').replace(/\s+/g, ' ');
            const nodes = Array.from(document.querySelectorAll('button,a,[role="button"],input[type="button"]'));
            const recognized = /^(?:下一(?:页|頁)|下一頁|次へ|次のページ|next(?: page)?|继续(?:阅读)?|繼續(?:閱讀)?|続きを読む)$/i;
            const choices = nodes.slice(0, 3000).filter(element => {
                if (!visible(element) || element.disabled || element.getAttribute('aria-disabled') === 'true' ||
                    element.closest('form') || element.hasAttribute('download') ||
                    (element.getAttribute('target') || '_self').toLowerCase() !== '_self') return false;
                if (element.hasAttribute('href')) {
                    const href = element.getAttribute('href').trim();
                    if (href && !/^javascript:\s*(?:void\(0\)|;)\s*;?$/i.test(href)) {
                        try {
                            if (new URL(href, document.baseURI).origin !== location.origin) return false;
                        } catch (_) { return false; }
                    }
                }
                return [element.getAttribute('aria-label'), element.getAttribute('title'), element.value,
                    element.textContent].some(text => recognized.test(label(text)));
            });
            let marker = null;
            const captions = Array.from(document.querySelectorAll('span,small,output,p,div,[aria-label]')).slice(0, 5000);
            for (const element of captions) {
                const raw = element.getAttribute('aria-label') || (element.children.length <= 3 ? element.textContent : '');
                if (!raw || raw.length > 42) continue;
                const text = label(raw);
                const match = /^(?:第\s*|page\s*)?(\d{1,4})\s*\/\s*(\d{1,4})(?:\s*(?:页|頁|ページ|pages?))?$/i.exec(text);
                if (!match || !visible(element)) continue;
                if (!/^(?:第|page)|(?:页|頁|ページ|pages?)$/i.test(text) && !element.closest(pager)) continue;
                const current = Number(match[1]), total = Number(match[2]);
                if (current > 0 && total >= current) { marker = {current, total}; break; }
            }
            const next = choices.find(element => element.closest(pager)) || choices[choices.length - 1] || null;
            return {next, current: marker?.current || 0, total: marker?.total || 0};
        }
    """.trimIndent()

    fun snapshot(): String = """
        (() => {
            $find
            if (!document.body) return null;
            const body = document.body.cloneNode(true);
            body.querySelectorAll('script,style,noscript,template,iframe,svg,canvas').forEach(node => node.remove());
            const html = body.outerHTML;
            if (html.length > 1500000) return null;
            const meta = document.querySelector('meta[property="og:title"]');
            const link = document.querySelector('link[rel~=next][href]');
            const controls = pageControls();
            return {url: location.href, title: (meta && meta.content || document.title).slice(0, 500), html,
                next: link ? link.href : '', current: controls.current, total: controls.total, hasControl: !!controls.next};
        })()
    """.trimIndent()

    fun click(expectedPage: String): String = """
        (() => {
            $find
            if (location.href !== $expectedPage) return false;
            const controls = pageControls();
            if (!controls.next || (controls.total > 0 && controls.total === controls.current)) return false;
            controls.next.click();
            return true;
        })()
    """.trimIndent()
}
