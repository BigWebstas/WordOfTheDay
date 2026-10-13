var FEED_URL = 'https://www.merriam-webster.com/wotd/feed/rss2';

// Watch fonts lack curly quotes, dashes and bullets; keep to what they can draw.
function toWatchText(s) {
  return s
    .replace(/[‘’]/g, "'")
    .replace(/[“”]/g, '"')
    .replace(/[–—]/g, '-')
    .replace(/…/g, '...')
    .replace(/[•\u0095]/g, '*');
}

function truncate(s, max) {
  if (s.length <= max) return s;
  return s.slice(0, max).replace(/\s+\S*$/, '') + '...';
}

function tag(xml, name) {
  var m = new RegExp('<' + name + '>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?</' + name + '>').exec(xml);
  return m ? m[1].trim() : '';
}

function htmlToText(html) {
  return html
    .replace(/<br\s*\/?>|<\/p>|<\/div>/g, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&#149;/g, '*')
    .replace(/&gt;/g, '>')
    .replace(/&lt;/g, '<')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .split('\n').map(function (l) { return l.trim(); }).join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

// The description is one text blob; split it on its fixed markers.
function sections(text) {
  var lines = text.split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
  var iExamples = lines.indexOf('Examples:');
  var iKnow = lines.indexOf('Did you know?');
  var headEnd = iExamples >= 0 ? iExamples : iKnow >= 0 ? iKnow : lines.length;
  var head = lines.slice(0, headEnd).filter(function (l) {
    return !/^Merriam-Webster's Word of the Day/.test(l) && !/^See the entry/.test(l);
  });
  var pronunciation = head.filter(function (l) { return l.indexOf('\\') >= 0; })[0] || '';
  var context = head.filter(function (l) { return l.indexOf('//') === 0; })[0] || '';
  var meaning = head.filter(function (l) { return l !== pronunciation && l !== context; }).join(' ');
  return {
    pronunciation: pronunciation.replace(/^[^\\]*/, '').replace(/\s*[*\u2022\u0095]\s*/g, ' ').replace(/\s+/g, ' ').trim(),
    meaning: meaning,
    context: context.replace(/^\/\/\s*/, ''),
    example: iExamples >= 0 ? lines.slice(iExamples + 1, iKnow > iExamples ? iKnow : lines.length).join(' ') : '',
    know: iKnow >= 0 ? lines.slice(iKnow + 1).join(' ') : ''
  };
}

function parseLatest(xml) {
  var item = /<item>([\s\S]*?)<\/item>/.exec(xml);
  if (!item) throw new Error('no items');
  var s = sections(htmlToText(tag(item[1], 'description')));
  var shortdef = tag(item[1], 'merriam:shortdef');
  var details = [];
  if (s.context) details.push('In context: ' + s.context);
  if (s.example) details.push('Example: ' + truncate(s.example, 200));
  if (s.know) details.push('Did you know? ' + s.know);
  return {
    WORD: toWatchText(tag(item[1], 'title')),
    DATE: tag(item[1], 'pubDate').split(' ').slice(0, 4).join(' '),
    PRONUNCIATION: toWatchText(s.pronunciation),
    MEANING: truncate(toWatchText(s.meaning || shortdef), 260),
    DETAILS: truncate(toWatchText(details.join('\n\n')), 480)
  };
}

function send(message) {
  Pebble.sendAppMessage(message, null, function () {
    console.log('Watch did not accept the word');
  });
}

function loadWord() {
  var req = new XMLHttpRequest();
  req.open('GET', FEED_URL);
  req.timeout = 10000;
  req.onload = function () {
    try {
      if (req.status !== 200) throw new Error('HTTP ' + req.status);
      send(parseLatest(req.responseText));
    } catch (e) {
      fail(e.message);
    }
  };
  req.onerror = req.ontimeout = function () { fail('Network error'); };
  req.send();
}

function fail(reason) {
  console.log('Feed failed: ' + reason);
  send({ WORD: 'Word of the Day', DATE: '', PRONUNCIATION: '', MEANING: 'Could not load the word.',
         DETAILS: 'Check your phone connection and reopen the app.' });
}

Pebble.addEventListener('ready', loadWord);
