var qstart = require('qstart'),
    map = require('./map'),
    cheminotm = require('./cheminotm'),
    phone = require('./phone'),
    analytics = require('./analytics');

var stream;

sessionStorage.clear();

qstart.then(function() {

  phone.init();

  map.init();

  window.addEventListener("message", function(message) {

    if(message.data && message.origin == window.location.origin) {

      if(message.data.event == 'cheminot:ready') {

        map.enableControls();

        document.body.classList.add('playing');

        if(!stream) {

          stream = Stream();
        }
      }

      if(message.data.event == 'cheminot:init') {

        if(message.data.error == 'full') {

          phone.unavailableDemo();

        }

      }

      if(message.data.event == 'cheminot:selecttrip') {

        map.disableTrace();

        map.displayTrip(message.data.trip, message.data.tdsp);

      }

      if(message.data.event == 'cheminot:unselecttrip') {

        map.clearTrips();

        map.clearTraces();

        map.enableTrace();

      }


      if(message.data.event == 'cheminot:selectstop') {

        cheminotm.getStop(message.data.stopId).then(function(stop) {

          map.addMarker(stop);

          map.fitMarkers();

        });

      }

      if(message.data.event == 'cheminot:resetstop') {

        map.removeMarker(message.data.stopId);

        map.fitMarkers();

      }

      if(message.data.event == 'cheminot:abort') {

      }
    }

  });

  (function() {

    var beta = document.querySelector('header .beta');

    beta.addEventListener('click', function(e) {

      e.preventDefault();

      analytics.trackDownloadBeta().fin(function() {

        window.location.href = beta.getAttribute('href');

      });

    });

  })();

  (function() {

    var todoList = document.querySelectorAll('.roadmap input[type=checkbox]');

    for (var i = 0; i < todoList.length; i++) {

      var todo = todoList.item(i);

      todo.addEventListener('click', function(e) {

        e.preventDefault();

      });
    }

  })();

  function Stream() {

    var baseURL = 'http://' + Settings.domain;

    var endpoint = baseURL + '/cheminotm/trace';

    var stream = new EventSource(baseURL + '/cheminotm/trace');

    stream.onmessage = function(msg) {

      var data = JSON.parse(msg.data);

      if(data) {

        map.displayTrace(data);

      }
    };

    stream.onerror = function(event) {

      console.log(event);

    };

    return stream;
  };
});
