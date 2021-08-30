function loadPrev() {
    search.prev(function(t) {
        load(t.responseObject());
    });
}

function loadNext() {
    search.next(function(t) {
        load(t.responseObject());
    });
}

function toBottom() {
    window.scrollTo(0, document.body.scrollHeight);
}

function toMiddle() {
    window.scrollTo(0, document.body.scrollHeight / 2);
}

function load(hits) {
    var numResults = (hits.length >= 100) ? hits.length + "+" : hits.length;
    document.getElementById("numberOfResults").innerHTML = "The number of results: " + numResults;

    const results = document.getElementById("results");
    while (results.firstChild) {
      results.removeChild(results.lastChild);
    }

    for (var i = 0; i < hits.length; i++) {
        var list = document.createElement("LI");
        var hit = hits[i];
        var a = document.createElement("a");
        var name = document.createTextNode(hit.searchName);

        a.href = hit.url;
        a.style.whitespace = "nowrap";

        a.appendChild(name);
        list.appendChild(a);

        var div = document.createElement("div");
        div.className = "collapsible";
        if (hit.showConsole) {
            for (var j = 0; j < hit.bestFragments.length; j++) {
                div.innerHTML = hit.bestFragments[j];
                list.appendChild(div);
            }
        }
        results.appendChild(list);
    }
    search.isFirstPage(function(t) {
        document.getElementById("btnPrev").style.display = (t.responseObject()) ? "none" : "";
    })
    search.isLastPage(function(t) {
        document.getElementById("btnNext").style.display = (t.responseObject()) ? "none" : "";
    })
    window.scrollTo(0, 0);
}