function rebuildDatabase() {
	var workers = document.querySelector(".txtWorkers").value;
	var jobs = document.querySelector(".txtJob").value;
	var overwrite = document.getElementById("selectOverwrite").value;
	if (workers < 1) {
		return;
	}
	luceneSearchManager.rebuildDatabase(workers, jobs, overwrite, function(t) {
		updateStatusFromResponse(t.responseObject());
	});
}

function getStatus() {
	luceneSearchManager.getStatus(function(t) {
		updateStatusFromResponse(t.responseObject());
	});
}

function abort() {
    luceneSearchManager.abort(function(t) {
        updateStatusFromResponse(t.responseObject());
    });
}

function clean() {
    luceneSearchManager.clean(function(t) {
        updateStatusFromResponse(t.responseObject());
    });
}

function updateStatusFromResponse(statement) {
	var messageElement = document.getElementById("message");
	messageElement.className = ((statement.code !== 0) ? "error" : "success");
	messageElement.innerHTML = statement.message;
  document.getElementById("luceneManagement").classList.toggle("jenkins-hidden", statement.running);
  document.getElementById("btnAbort").classList.toggle("jenkins-hidden", !statement.running);
	if (statement.progress) {
    document.getElementById("currentProgress").classList.remove("jenkins-hidden");
		var progress = statement.progress;
		document.getElementById("currentWorkers").innerHTML = statement.workers;
		document.getElementById("currentlyProcessing").innerHTML = progress.name;
		document.getElementById("currentlyProcessingIndex").textContent = progress.current;
		document.getElementById("currentlyProcessingMax").textContent = progress.max;
		document.getElementById("totalProcessesedRun").textContent = progress.processedItems;
		document.getElementById("currentElapsedTime").textContent = (progress.elapsedTime / 1000)
				+ "s";
		var historyString = "";
		for (var historyIndex = 0; historyIndex < progress.history.length; historyIndex++) {
			var hist = statement.progress.history[historyIndex];
			var projectString = hist.name + " completed after "
					+ (hist.elapsedTime / 1000) + "s (" + hist.current
					+ " elements processed)";
			if (hist.reasonMessage) {
				historyString += "<span class=\"historyerror\">"
						+ projectString + ": " + hist.reasonMessage
						+ "</span><br />";
			} else {
				historyString += "<span class=\"historysuccess\">"
						+ projectString + "</span><br />";
			}
		}
		document.getElementById("history").innerHTML = historyString;
	} else {
		document.getElementById("currentProgress").classList.add("jenkins-hidden");
	}

}

Behaviour.specify("#btnRebuild", "lucene-rebuild", 0, function(button) {
  button.onclick = function() {
    rebuildDatabase();
  }
});

Behaviour.specify("#btnClean", "lucene-clean", 0, function(button) {
  button.onclick = function() {
    clean();
  }
});

Behaviour.specify("#btnAbort", "lucene-abort", 0, function(button) {
  button.onclick = function() {
    abort();
  }
});

document.addEventListener('DOMContentLoaded', function() {
  getStatus();
  window.setInterval(function (a, b) {getStatus();}, 2000);
});
