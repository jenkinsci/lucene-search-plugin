function rebuildDatabase() {
    var workers = txtWorkers = parseInt(Q("#txtWorkers").val());
    if(workers < 1){
        return;
    }
    console.log(workers);
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
        Q(".running").toggle(statement.running);
        Q(".stopped").toggle(!statement.running);
        updateStatusFromResponse(statement);
    });
}

function updateStatusFromResponse(statement) {
    console.log(statement);
    Q("#message").toggleClass("error", statement.code !== 0).text(statement.message);
    if(statement.progress) {
        Q("#currentWorkers").text(statement.workers);
        Q("#history").empty();
        Q("#currentProgress").show();
        var progress = statement.progress;
        Q("#currentlyProcessing").text(progress.name);
        Q("#currentlyProcessingIndex").text(progress.current);
        Q("#currentlyProcessingMax").text(progress.max);
        Q("#totalProcessesedRun").text(progress.processedItems);
        Q("#currentElapsedTime").text((progress.elapsedTime/1000) + "s");
        
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
        Q("#currentProgress").hide();
    }
}