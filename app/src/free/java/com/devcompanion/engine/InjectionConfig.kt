package com.devcompanion.engine

/**
 * JS injections to apply on each page load.
 * Free flavor (WebView): all injections needed for rendering fixes.
 * Gecko flavor (GeckoView): none needed — engine handles rendering correctly.
 */
object InjectionConfig {
    /** Whether this flavor needs JS injection fixes (WebView does, GeckoView doesn't). */
    val needsInjections: Boolean = true

    /**
     * Whether the WebView heartbeat (JS freeze detection) should be active.
     * Only meaningful for WebView — GeckoView doesn't freeze from MutationObserver loops.
     */
    val needsHeartbeat: Boolean = true

    // ── JS Injection Constants (WebView-only) ────────────────────────

    /** Inject autocomplete/autofill attributes into form fields. */
    val AUTOFILL_INJECTION = """(function(){
    if (window.__dcAutofill) return "already-injected";
    window.__dcAutofill = true;
    var autofillMap = {
        'username': ['text','email'],
        'email': ['email'],
        'current-password': ['password'],
        'new-password': ['password'],
        'tel': ['tel'],
        'street-address': ['text'],
        'address-line1': ['text'],
        'address-line2': ['text'],
        'address-level1': ['text'],
        'address-level2': ['text'],
        'postal-code': ['text'],
        'country': ['text'],
        'country-name': ['text'],
        'cc-number': ['text'],
        'cc-exp': ['text'],
        'cc-exp-month': ['text'],
        'cc-exp-year': ['text'],
        'cc-csc': ['text'],
        'cc-given-name': ['text'],
        'cc-family-name': ['text'],
        'cc-name': ['text'],
        'cc-type': ['text']
    };
    function inferAutocomplete(input) {
        if (input.autocomplete && input.autocomplete !== 'off') return;
        var type = (input.type || 'text').toLowerCase();
        var name = (input.name || '').toLowerCase();
        var id = (input.id || '').toLowerCase();
        var hint = '';
        if (type === 'email' || name.includes('email') || id.includes('email')) hint = 'email';
        else if (type === 'password' || name.includes('pass') || id.includes('pass')) {
            hint = name.includes('new') || id.includes('new') ? 'new-password' : 'current-password';
        }
        else if (type === 'tel' || name.includes('phone') || id.includes('phone')) hint = 'tel';
        else if (name.includes('user') || id.includes('user') || name.includes('login') || id.includes('login')) hint = 'username';
        else if (name.includes('search') || id.includes('search')) hint = 'off';
        if (hint) input.setAttribute('autocomplete', hint);
    }
    var inputs = document.querySelectorAll('input');
    for (var i = 0; i < inputs.length; i++) {
        inferAutocomplete(inputs[i]);
    }
})();"""

    /**
     * Provide CSS custom property --webview-vh and prevent framework drawers
     * from collapsing to 0px.
     *
     * Problem: Vuetify v-navigation-drawer uses a ResizeObserver to compute
     * its height. In Android WebView, the initial viewport dimensions can be
     * misreported (especially with useWideViewPort + setInitialScale), causing
     * Vuetify to compute drawer height = 0px. Vuetify explicitly sets both
     * `style.height = "0px"` and `style.minHeight = "0px"` via JS, overriding
     * any CSS rules including !important.
     *
     * Solution: A MutationObserver watches for style changes on navigation
     * drawers and restores height to 100vh when it collapses to 0. This runs
     * after Vuetify's reactive cycle, so it wins the specificity war.
     *
     * Also removed: document.documentElement.style.zoom — it breaks JS
     * framework layout calculations (Vuetify ResizeObserver reads zoom-
     * affected offsetHeight and computes drawer height = 0px).
     */
    val VH_FIX_INJECTION = """(function(){
    if (window.__dcVhFix) return "already-injected";
    window.__dcVhFix = true;

    // ── CSS custom property --webview-vh ────────────────────────────
    function setVh() {
        document.documentElement.style.setProperty('--webview-vh', (window.innerHeight * 0.01) + 'px');
    }
    setVh();
    window.addEventListener('resize', setVh);

    // ── Drawer collapse guard ───────────────────────────────────────
    // Vuetify sets style.height="0px" and style.minHeight="0px" on
    // v-navigation-drawer when it computes the wrong height.
    // We observe style mutations and restore height to the full viewport
    // whenever it collapses to 0. Closed mobile drawers are excluded via
    // the --close or --is-mobile (without --open) class check.
    var _fixing = false;

    function isDrawerOpen(el) {
        if (el.classList.contains('v-navigation-drawer--close') &&
            !el.classList.contains('v-navigation-drawer--open')) {
            // Explicitly closed mobile drawer — allow height=0
            return false;
        }
        return true;
    }

    function fixDrawerHeight(el) {
        if (_fixing) return;
        _fixing = true;
        try {
            if (!isDrawerOpen(el)) return;
            var h = el.style.height;
            if (h === '0px' || h === '') {
                el.style.setProperty('height', '100vh', 'important');
                el.style.setProperty('min-height', '100vh', 'important');
            }
            // Also fix inner content
            var content = el.querySelector('.v-navigation-drawer__content');
            if (content) {
                var ch = content.style.height;
                if (ch === '0px' || ch === '') {
                    content.style.setProperty('height', '100vh', 'important');
                    content.style.setProperty('min-height', '100vh', 'important');
                }
            }
        } finally {
            _fixing = false;
        }
    }

    function observeDrawer(el) {
        var mo = new MutationObserver(function() { fixDrawerHeight(el); });
        mo.observe(el, { attributes: true, attributeFilter: ['class', 'style'] });
        fixDrawerHeight(el);
    }

    // Observe existing drawers
    document.querySelectorAll('.v-navigation-drawer, .navigation-container').forEach(observeDrawer);

    // Observe future drawers added to DOM
    var bodyMo = new MutationObserver(function(mutations) {
        mutations.forEach(function(m) {
            m.addedNodes.forEach(function(node) {
                if (node.nodeType === 1) {
                    if (node.matches && node.matches('.v-navigation-drawer, .navigation-container')) {
                        observeDrawer(node);
                    }
                    node.querySelectorAll && node.querySelectorAll('.v-navigation-drawer, .navigation-container').forEach(observeDrawer);
                }
            });
        });
    });
    bodyMo.observe(document.body || document.documentElement, { childList: true, subtree: true });
})();"""

    /** Fix text-size-adjust for WebView. */
    val TEXT_SIZE_FIX_INJECTION = """(function(){
    if (window.__dcTextSizeFix) return "already-injected";
    window.__dcTextSizeFix = true;
    var style = document.createElement('style');
    style.textContent = [
        'html {',
        '  -webkit-text-size-adjust: 100% !important;',
        '  -ms-text-size-adjust: 100% !important;',
        '  text-size-adjust: 100% !important;',
        '}',
        'body {',
        '  -webkit-text-size-adjust: 100% !important;',
        '  text-size-adjust: 100% !important;',
        '}'
    ].join('\\n');
    document.head.appendChild(style);
})();"""

    /** WebView heartbeat: JS engine updates timestamp every second. */
    val HEARTBEAT_INJECTION = """(function(){
    if (window.__dcHeartbeat) return "already-injected";
    window.__dcHeartbeat = true;
    window.__devCompanionHeartbeat = Date.now();
    setInterval(function() {
        window.__devCompanionHeartbeat = Date.now();
    }, 1000);
})();"""

    /** Inspector iframe injection for element highlighting. */
    val INSPECTOR_IFRAME_INJECTION = """(function(){
    function injectToIframe(iframe){
        try{
            var doc=iframe.contentDocument||iframe.contentWindow.document;
            if(!doc||doc.getElementById('__devOverlay')) return;
            var script=doc.createElement('script');
            script.textContent=`(function(){
                if(document.getElementById('__devOverlay')) return;
                var overlay=document.createElement('div');
                overlay.id='__devOverlay';
                overlay.style.cssText='position:fixed;pointer-events:none;z-index:999999;border:2px solid #4CAF50;background:rgba(76,175,80,0.1);border-radius:2px;transition:all 0.15s ease;display:none;';
                document.body.appendChild(overlay);
                function getXPath(el){
                    if(el.id) return '//*[@id=\"'+el.id+'\"]';
                    var parts=[];
                    while(el&&el.nodeType===1){
                        var idx=1,sib=el.previousSibling;
                        while(sib){if(sib.nodeType===1&&sib.tagName===el.tagName)idx++;sib=sib.previousSibling;}
                        var tag=el.tagName.toLowerCase();
                        parts.unshift(tag+'['+idx+']');
                        el=el.parentNode;
                    }
                    return '/'+parts.join('/');
                }
                function getCssSelector(el){
                    if(el.id) return '#'+el.id;
                    var sel=el.tagName.toLowerCase();
                    if(el.className&&typeof el.className==='string'){
                        var cls=el.className.trim().split(/\\s+/).filter(function(c){return c;}).join('.');
                        if(cls) sel+='.'+cls;
                    }
                    return sel;
                }
                function handler(e){
                    var target=e.target;
                    if(target===overlay) return;
                    e.preventDefault();
                    e.stopPropagation();
                    var rect=target.getBoundingClientRect();
                    overlay.style.left=rect.left+'px';
                    overlay.style.top=rect.top+'px';
                    overlay.style.width=rect.width+'px';
                    overlay.style.height=rect.height+'px';
                    overlay.style.display='block';
                    var attrs={};
                    for(var i=0;i<target.attributes.length;i++){
                        var a=target.attributes[i];
                        attrs[a.name]=a.value;
                    }
                    var data={
                        tagName:target.tagName,
                        id:target.id||null,
                        className:target.className&&typeof target.className==='string'?target.className:null,
                        xpath:getXPath(target),
                        cssSelector:getCssSelector(target),
                        textContent:target.textContent?target.textContent.substring(0,200):null,
                        attributes:attrs,
                        boundingRect:{left:rect.left,top:rect.top,width:rect.width,height:rect.height}
                    };
                    if(window.parent&&window.parent.__devCompanionInspector){
                        window.parent.__devCompanionInspector.post(JSON.stringify(data));
                    } else if(window.__devCompanionInspector){
                        window.__devCompanionInspector.post(JSON.stringify(data));
                    }
                }
                document.addEventListener('touchstart',handler,{capture:true,passive:false});
                document.addEventListener('click',handler,true);
            })();`;
            doc.head.appendChild(script);
        }catch(e){}
    }
    var iframes=document.querySelectorAll('iframe');
    for(var i=0;i<iframes.length;i++){
        injectToIframe(iframes[i]);
    }
})();"""
}