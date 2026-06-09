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

    /** Fix Android WebView vh/dvh unit miscalculation. */
    val VH_FIX_INJECTION = """(function(){
    var h = window.innerHeight;
    var VH_MARKER = '__vhFixApplied';
    var LOOP_LIMIT = 20;
    var loopCount = 0;
    var debounceTimer = null;
    var observerActive = true;

    document.documentElement.style.setProperty('--webview-vh', (h * 0.01) + 'px');

    function fixVhUnits() {
        h = window.innerHeight;
        document.documentElement.style.setProperty('--webview-vh', (h * 0.01) + 'px');

        var inlineSelectors = '.v-navigation-drawer, .navigation-container, .v-navigation-drawer__content, [style*="100vh"], [style*="100dvh"]';
        document.querySelectorAll(inlineSelectors).forEach(function(el) {
            if (el.dataset[VH_MARKER] === '1') return;
            var style = el.getAttribute('style') || '';
            if (style.includes('100vh') || style.includes('100dvh')) {
                el.style.setProperty('height', h + 'px', 'important');
                el.dataset[VH_MARKER] = '1';
            }
        });

        var asideSelectors = '.v-navigation-drawer aside, .navigation-container aside, .system-menu, .system-depth-menu';
        document.querySelectorAll(asideSelectors).forEach(function(el) {
            if (el.dataset[VH_MARKER] === '1') return;
            var computedHeight = window.getComputedStyle(el).height;
            if (computedHeight === '0px' && el.children.length > 0) {
                var marginTop = parseInt(window.getComputedStyle(el).marginTop) || 0;
                el.style.setProperty('height', (h - marginTop) + 'px', 'important');
                el.style.removeProperty('bottom');
                el.dataset[VH_MARKER] = '1';
            }
        });

        document.querySelectorAll('.v-navigation-drawer__content').forEach(function(el) {
            if (el.dataset[VH_MARKER] === '1') return;
            var computedHeight = window.getComputedStyle(el).height;
            if (computedHeight === '0px') {
                el.style.setProperty('height', '100%', 'important');
                el.dataset[VH_MARKER] = '1';
            }
        });
    }
    fixVhUnits();

    var observer = new MutationObserver(function(mutations) {
        if (!observerActive) return;
        var externalChange = false;
        mutations.forEach(function(m) {
            if (m.type === 'attributes' && m.attributeName === 'style') {
                var target = m.target;
                if (target.dataset && target.dataset[VH_MARKER] === '1') return;
                var style = target.getAttribute('style') || '';
                if (style.includes('100vh') || style.includes('100dvh') ||
                    style.includes('height: 0px') || style.includes('height:0px')) {
                    externalChange = true;
                }
                if (target.classList && (
                    target.classList.contains('v-navigation-drawer') ||
                    target.classList.contains('system-menu') ||
                    target.classList.contains('system-depth-menu') ||
                    target.classList.contains('navigation-container')
                )) {
                    delete target.dataset[VH_MARKER];
                    externalChange = true;
                }
            }
        });
        if (!externalChange) return;
        if (debounceTimer) clearTimeout(debounceTimer);
        loopCount++;
        if (loopCount > LOOP_LIMIT) {
            observer.disconnect();
            observerActive = false;
            console.warn('[DevCompanion VH_FIX] Observer disconnected: loop limit reached');
            return;
        }
        debounceTimer = setTimeout(function() {
            fixVhUnits();
            loopCount = 0;
        }, 100);
    });
    observer.observe(document.documentElement, {
        attributes: true,
        subtree: true,
        attributeFilter: ['style']
    });

    window.addEventListener('resize', function() {
        document.querySelectorAll('[data-' + VH_MARKER + ']').forEach(function(el) {
            delete el.dataset[VH_MARKER];
        });
        fixVhUnits();
    });
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