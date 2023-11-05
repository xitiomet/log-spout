var connection;
var term;
var fitAddon = new FitAddon.FitAddon();
var command = '';
var debugMode = false;
var reconnectTimeout;
var hostname = location.hostname;
var protocol = location.protocol;
var port = location.port;
var wsProtocol = 'ws';
var httpUrl = '';
var termAuth = '';

function getParameterByName(name, url = window.location.href) 
{
    name = name.replace(/[\[\]]/g, '\\$&');
    var regex = new RegExp('[?&]' + name + '(=([^&#]*)|&|#|$)'),
        results = regex.exec(url);
    if (!results) return null;
    if (!results[2]) return '';
    return decodeURIComponent(results[2].replace(/\+/g, ' '));
}

function padString(str, len) {
  if (str.length < len) {
    return str.padEnd(len, ' ');
  } else if (str.length > len) {
    return str.slice(0, len);
  } else {
    return str;
  }
}

function switchLED(color)
{
    if (color == 'red')
    {
        document.getElementById('redLed').style.display = 'inline-block';
    } else {
        document.getElementById('redLed').style.display = 'none';
    }
    if (color == 'green')
    {
        document.getElementById('greenLed').style.display = 'inline-block';
    } else {
        document.getElementById('greenLed').style.display = 'none';
    }
    if (color == 'grey')
    {
        document.getElementById('greyLed').style.display = 'inline-block';
    } else {
        document.getElementById('greyLed').style.display = 'none';
    }
}

function updateKCS(v)
{
    if (v)
    {
        switchLED('green');
    } else {
        switchLED('red');
    }
}

function popupWindow(url, windowName, w, h) {
    const y = window.top.outerHeight / 2 + window.top.screenY - ( h / 2);
    const x = window.top.outerWidth / 2 + window.top.screenX - ( w / 2);
    return window.open(url, windowName, `toolbar=no, location=no, directories=no, status=no, menubar=no, scrollbars=no, resizable=no, copyhistory=no, width=${w}, height=${h}, top=${y}, left=${x}`);
}

function fitStuff()
{
    var terminalElement = document.getElementById('terminal');
    if (terminalElement != undefined)
    {
        terminalElement.style.height = (window.innerHeight - 96) + 'px';
        terminalElement.style.width = (window.innerWidth - 5) + 'px';
        setTimeout(() => {
            fitAddon.fit();
        },50);
    }
}


window.onresize = function() {
    fitStuff();
}

function runFakeTerminal() 
{
    if (term._initialized) {
      return;
    }
    term._initialized = true;
}

function sendEvent(wsEvent)
{
    var out_event = JSON.stringify(wsEvent);
    if (debugMode)
        console.log("Transmit: " + out_event);
    try
    {
        connection.send(out_event);
    } catch (err) {
        console.log(err);
    }
}

function updateFilter()
{
    var filter = document.getElementById('filterInput').value;
    sendEvent({"filter": filter});
}

function updateLog()
{
    var log = document.getElementById('logs').value;
    sendEvent({"log": log});
}

function doAuth()
{
    sendEvent({
        "apiPassword": document.getElementById('password').value,
        "termId": Date.now()
    });
}

function filterIp()
{
    document.getElementById('filterIp').enabled = false;
    $.get("https://ipv4.lafibre.info/ip.php").done((ipv4) => {
        $.get("https://ipv6.lafibre.info/ip.php").done((ipv6) => {
            var filterInput = document.getElementById('filterInput');
            if (filterInput.value == '')
            {
                var filterText = '(' + ipv4 + ' || ' + ipv6 + ')';
                filterInput.value = filterText;
            } else {
                var filterText = '(' + filterInput.value + ') && (' + ipv4 + ' || ' + ipv6 + ')';
                filterInput.value = filterText;
            }
            updateFilter();
            document.getElementById('filterIp').enabled = true;
        });
    });
    
}

function setupWebsocket()
{
    try
    {
        if (hostname == '')
        {
            debugMode = true;
            hostname = '172.19.191.115';
            protocol = 'http';
            port = 8662;
            httpUrl = "http://" + hostname + ":8662/";
        }
        if (protocol.startsWith('https'))
        {
            wsProtocol = 'wss';
        }
        connection = new WebSocket(wsProtocol + '://' + hostname + ':' + port + '/logspout/');
        
        connection.onopen = function () {
            if (document.getElementById('login').style.display == 'none')
            {
                doAuth();
            }
        };
        
        connection.onerror = function (error) {
            //document.getElementById('connectionLed').src="led-grey.svg";
        };

        //Code for handling incoming Websocket messages from the server
        connection.onmessage = function (e) {
            if (debugMode)
            {
                console.log("Receive: " + e.data);
            }
            var jsonObject = JSON.parse(e.data);
            if (jsonObject.hasOwnProperty("name"))
            {
                document.getElementById('instancename').innerHTML = jsonObject.name;
            }
            if (jsonObject.hasOwnProperty("action"))
            {
                var action = jsonObject.action;
                if (action == 'authOk') {
                    hostname = jsonObject.hostname;
                    document.getElementById('login').style.display = 'none';
                    document.getElementById('terminalScreen').style.display = 'block';
                    document.getElementById('filterInput').style.display = 'inline-block';
                    document.getElementById('logs').style.display = 'inline-block';
                    document.getElementById('filterIp').style.display = 'inline-block';
                    var logsSelector = document.getElementById('logs');
                    logsSelector.innerHTML = "";
                    var first = true;
                    for(logName of jsonObject.logs)
                    {
                        var opt = document.createElement("option");
                        opt.value = logName;
                        opt.innerHTML = logName;
                        if (first)
                        {
                            opt.checked = true;
                            first = false;
                        }
                        logsSelector.appendChild(opt);
                    }
                    termAuth = jsonObject.termAuth;
                    updateKCS(true)
                    sendEvent({
                        "history": 100
                    });
                    runFakeTerminal();
                    fitStuff();
                    updateLog();
                    updateFilter();
                } else if (action == 'authFail') {
                    document.getElementById('errorMsg').innerHTML = jsonObject.error;
                } else if (action == 'line') {
                    if (document.getElementById('autoscroll').checked)
                    {
                        term.writeln(jsonObject.line);
                        term.scrollToBottom();
                    }
                }
            }
        };
        
        connection.onclose = function () 
        {
            switchLED('grey');
            console.log('WebSocket connection closed');
            reconnectTimeout = setTimeout(setupWebsocket, 10000);
        };
    } catch (err) {
        console.log(err);
    }
}

function getDTString(date)
{
    var now = new Date();
    var nowDateString = now.toLocaleDateString();
    var dateString = date.toLocaleDateString();
    var timeString = date.toLocaleTimeString();
    if (nowDateString != dateString)
    {
        return dateString + ' ' + timeString;
    } else {
        return timeString;
    }
}

String.prototype.hashCode = function() {
    var hash = 0,
      i, chr;
    if (this.length === 0) return hash;
    for (i = 0; i < this.length; i++) {
      chr = this.charCodeAt(i);
      hash = ((hash << 5) - hash) + chr;
      hash |= 0; // Convert to 32bit integer
    }
    return hash;
  }

function cleanPayload(payload)
{
    if (payload != undefined)
        return payload.replaceAll(/</g,'&lt;').replaceAll(/>/g,'&gt;').replaceAll(/\r/g,'&lt;CR&gt;').replaceAll(/\n/g,'&lt;LF&gt;');
    else
        return "";
}

window.onload = function() {
    term = new Terminal({cursorBlink: false, allowProposedApi: true, scrollback: 200, fontSize: 10});
    term.open(document.getElementById('terminal')); 
    term.loadAddon(fitAddon);
    setupWebsocket();
};

