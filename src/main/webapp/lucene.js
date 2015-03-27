function rebuildDatabase() {
    var workers = txtWorkers = parseInt($("#txtWorkers").val());
    if(workers < 1){
        return;
    }
    luceneSearchManager.rebuildDatabase(workers, function(t) {
        var statement = t.responseObject();
        updateStatusFromResponse(statement);
        getStatus();
    });
}

function abort() {
    luceneSearchManager.abort(function(t) {
        var statement = t.responseObject();
        updateStatusFromResponse(statement);
    });
}

function getStatus() {
    luceneSearchManager.getStatus(function(t) {
        var statement = t.responseObject();
        $(".running").toggle(statement.running);
        $(".stopped").toggle(!statement.running);
        updateStatusFromResponse(statement);
    });
}

$(document).ready(function() {
    getStatus();
    window.setInterval(function (a, b) {
        //getStatus();
    }, 5000);
});

function updateStatusFromResponse(statement) {
    console.log(statement);
    $("#message").toggleClass("error", statement.code !== 0).text(statement.message);
    if(statement.progress) {
        $("#currentWorkers").text(statement.workers);
        $("#history").empty();
        $("#currentProgress").show();
        var progress = statement.progress;
        $("#currentlyProcessing").text(progress.name);
        $("#currentlyProcessingIndex").text(progress.current);
        $("#currentlyProcessingMax").text(progress.max);
        $("#totalProcessesedRun").text(progress.processedItems);
        $("#currentElapsedTime").text((progress.elapsedTime/1000) + "s");
        
        for(var historyIndex = 0; historyIndex != progress.history.length; historyIndex++) {
            var hist = statement.progress.history[historyIndex];
            var projectString = hist.name + " completed after " + (hist.elapsedTime/1000) + "s (" + hist.current + " elements processed)";
            var node = $(document.createElement("li"));
            if (hist.reasonMessage) {
                node.addClass("error").text(projectString + ": " + hist.reasonMessage);
            } else {
                node.addClass("success").text(projectString);
            }
            $("#history").append(node);
        }
    } else {
        $("#currentProgress").hide();
    }
}