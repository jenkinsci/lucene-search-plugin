function updateStatusFromResponse(statement) {
    console.log(statement);
    var html = statement.message;
    if(statement.progress != null) {
        html += "<br />Currently processing " + statement.progress.name + " <b>" + statement.progress.currentProject.name + "</b>";
        html += "<br />Project <b>" + statement.progress.current + "</b> out of <b>" + statement.progress.max + "</b>";
        html += "<br /><b>Processed projects</b><br />";
        html += "<ul>";
        for (var historyIndex = 0; historyIndex < statement.progress.history.length; historyIndex++) {
            var hist = statement.progress.history[historyIndex];
            var projectString = hist.name + " completed after " + (hist.elapsedTime/1000) + "s";
            if(hist.reasonMessage != ""){
                html += "<li><span style=\"error\">" + projectString + ": " + hist.reasonMessage + "</span></li>";
            }else{
                html += "<li><span style=\"success\">" +projectString+ "</span></li>";
            }
            //html += "<br />";   
        }
        html +="</ul>";
    }
    $("#jenkinsResponse").toggleClass("error", statement.code !== 0).html(html);
}