//--------------------------------------------------
// App components
//--------------------------------------------------
var express = require('express')
  , stylus = require('stylus')
  , nib = require('nib')
  , app = express();

function compile(str, path) {
  return stylus(str)
    .set('filename', path)
    .use(nib());
}

//--------------------------------------------------
// Configure views (jade) and css (stylus)
//--------------------------------------------------
app.set('views', __dirname + '/views');
app.set('view engine', 'jade');
app.use(express.logger('dev'));   // TODO: turn off when not dev
app.use(stylus.middleware({
  src: __dirname + '/public',
  compile: compile
}));
app.use(express.static(__dirname + '/public'));

//--------------------------------------------------
// Configure routes
//--------------------------------------------------
app.get('/', function(req, res) {
  res.render('index', {
    title: 'Home',
    leaders: [
      ['GAB', formatTime(10000)],
      ['ABC', formatTime(10001)],
      ['DEF', formatTime(10002)],
      ['GHI', formatTime(10003)],
      ['JKL', formatTime(10004)],
      ['MNO', formatTime(10005)],
      ['PQR', formatTime(10006)],
      ['STU', formatTime(10007)],
      ['VWX', formatTime(10008)],
      ['YZ1', formatTime(10009)]
    ]
  });
});

//--------------------------------------------------
// Run server
// n.b. args: [node, script.js, port]
//--------------------------------------------------
if (process.argv.length != 3) {
  console.error('usage: server.js port');
}
else {
  var port = process.argv[2];
  app.listen(port);
  console.log('Listening on port ' + port);
}

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
