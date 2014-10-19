function rebuildDatabase(luceneSearchManager) {
	console.log(luceneSearchManager);
	luceneSearchManager
			.rebuildDatabase(function(t) {
				statement = t.responseObject();
				console.log(statement);
				document.getElementById("jenkinsResponse").className = (statement.code == 0) ? "success"
						: "error";
				document.getElementById("jenkinsResponse").innerHTML = statement.message;
			});
}

function clearDatabase(luceneSearchManager) {
	luceneSearchManager
			.clearDatabase(function(t) {
				statement = t.responseObject();
				document.getElementById("jenkinsResponse").className = (statement.code == 0) ? "success"
						: "error";
				document.getElementById("jenkinsResponse").innerHTML = statement.message;
			});
}

function abort(luceneSearchManager) {
	luceneSearchManager
			.abort(function(t) {
				statement = t.responseObject();
				document.getElementById("jenkinsResponse").className = (statement.code == 0) ? "success"
						: "error";
				document.getElementById("jenkinsResponse").innerHTML = statement.message;
			});
}

function abort(luceneSearchManager) {
	luceneSearchManager
			.abort(function(t) {
				statement = t.responseObject();
				document.getElementById("jenkinsResponse").className = (statement.code == 0) ? "success"
						: "error";
				document.getElementById("jenkinsResponse").innerHTML = statement.message;
			});
}

function getStatus(luceneSearchManager) {
	luceneSearchManager
			.getStatus(function(t) {
				statement = t.responseObject();
				document.getElementById("jenkinsResponse").className = (statement.code == 0) ? "success"
						: "error";
				document.getElementById("jenkinsResponse").innerHTML = statement.message;
			});
}

function configureProgress(luceneSearchManager) {
	try {
		luceneSearchManager.getStatus(function(t) {
			console.log(t);
			console.log();

			// luceneProgress
		});
	} catch (exc) {
		console.log("S");
		console.log(exc);
	}

}