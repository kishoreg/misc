$(document).ready(function() {
  var intervalId;
  var running = false;

  // Start handler (should be left mouse click)
  var toggle = function() {
    if (running) {
      // Stop update timer
      clearInterval(intervalId);

      // TODO: POST request to server, which relays to espresso
      
      // Update the latest time
      $("#latest-name")[0].innerHTML = $("#name").val();
      $("#latest-time")[0].innerHTML = $("#console")[0].innerHTML;
      
      // Set in stopped mode
      running = false;
    }
    else {
      // Don't start unless there's a name
      if ($("#name").val() == '') {
        $("#name").val('ASS');
      }
      // Ferris thinks this will be funny to fuck with Naveen
      else if ($("#name").val() == 'NAV')
      {
        $("#name").val('FU');
      }
      
      // Record starting time
      var startTime = new Date();

      // Updates display with number of seconds that have elapsed every second
      var update = function() {
        $("#console")[0].innerHTML = formatTime(new Date() - startTime);
      }
      intervalId = setInterval(update, 1);
      update();

      // Set in running mode
      running = true;
    }
  };

  // Handlers for mouse clicks (from decomposed wireless mouse)
  $(document).mousedown(function(event) {
    switch (event.which) {
      case 1: // left mouse
        toggle();
        break;
      case 2: // middle mouse
        break;
      case 3: // right mouse
        toggle();
        break;
      default: // non-standard mouse
    }
  });

  // Always re-select the name input
  $(document).mouseup(function(event) {
    // Set focus to name input
    $("#name").trigger('focus');
  });

  // Disable right click context menu
  window.oncontextmenu = function() { return false; }

  // Initialize timer
  $("#console")[0].innerHTML = formatTime(0);

  // Set focus to name input
  $("#name").trigger('focus');
});

// Outputs in minutes{2}:seconds{2}:millis{3}
function formatTime(d) {
  date = new Date(d);
  output = "";

  // Add minutes
  if (date.getMinutes() < 10)
    output += "0";
  output += date.getMinutes();

  output += ":";

  // Add seconds
  if (date.getSeconds() < 10)
    output += "0";
  output += date.getSeconds();

  output += ".";

  // Add millis
  if (date.getMilliseconds() < 100)
    output += "0";
  if (date.getMilliseconds() < 10)
    output += "0";
  output += date.getMilliseconds();

  return output;
}
