(function (global) {
  'use strict';
  var CONFIG_URL = '/api/maps-config';
  var SDK_JS = 'https://maps.googleapis.com/maps/api/js';
  var LOAD_TIMEOUT = 15000;
  var FAIL_MESSAGE = 'Google Maps could not load. Check that Maps JavaScript API, billing and this local address are allowed for the key.';
  var configPromise = null, sdkPromise = null, sdkFailed = false, authFailed = false, authFailureInstalled = false;
  var generation = 0, ownerRoot = null, ownerShell = null, ownerStatus = null, ownerControls = null, ownerLegend = null, ownerFallback = null, ownerToken = 0;
  var map = null, mapEl = null, dataLayer = null, neighborLayer = null, pinLayer = null, pinFeature = null, neighborInfoWindow = null, neighborInfoContent = null, showBoundaries = true, showNeighbors = true;

  function getConfig() {
    if (configPromise) return configPromise;
    configPromise = fetch(CONFIG_URL, { cache: 'no-store' }).then(function (res) {
      if (!res || !res.ok) throw new Error('config');
      return res.json();
    }).then(function (cfg) {
      return (cfg && cfg.enabled === true && typeof cfg.key === 'string' && cfg.key) ? { enabled: true, key: cfg.key } : { enabled: false };
    });
    return configPromise;
  }

  function installAuthFailure() {
    if (authFailureInstalled) return;
    authFailureInstalled = true;
    var previous = global.gm_authFailure;
    global.gm_authFailure = function () {
      authFailed = true;
      sdkFailed = true;
      if (typeof previous === 'function') { try { previous(); } catch (e) {} }
      showFailureForOwner();
    };
  }

  function closeNeighborInfo() {
    if (neighborInfoWindow) neighborInfoWindow.close();
  }

  function clearNeighborFeatures() {
    closeNeighborInfo();
    if (neighborLayer) {
      neighborLayer.forEach(function (feature) { neighborLayer.remove(feature); });
    }
  }

  function openNeighborInfo(event) {
    if (!neighborInfoWindow || !neighborInfoContent || !event || !event.feature) return;
    var name = event.feature.getProperty('name');
    if (typeof name !== 'string' || !name) name = 'Neighboring boundary';
    neighborInfoContent.textContent = name;
    if (event.latLng) neighborInfoWindow.setPosition(event.latLng);
    neighborInfoWindow.open(map);
  }

  function updateLegend(legend, neighborCount, neighborTotal, neighborTruncated, neighborOmitted) {
    if (!legend) return;
    while (legend.firstChild) legend.removeChild(legend.firstChild);
    function legendItem(swatchClass, text) {
      var item = document.createElement('span');
      item.className = 'locality-google-map-legend__item';
      var swatch = document.createElement('span');
      swatch.className = 'locality-google-map-legend__swatch ' + swatchClass;
      swatch.setAttribute('aria-hidden', 'true');
      item.appendChild(swatch);
      var label = document.createElement('span');
      label.textContent = text;
      item.appendChild(label);
      return item;
    }
    legend.appendChild(legendItem('locality-google-map-legend__swatch--selected', 'Green: selected'));
    legend.appendChild(legendItem('locality-google-map-legend__swatch--neighbor', 'Blue: neighboring'));
    var countText = neighborCount + ' nearby outlines';
    if (neighborTruncated) {
      countText += neighborTotal > neighborCount
        ? ' (showing ' + neighborCount + ' of ' + neighborTotal + ')'
        : ' (truncated)';
    }
    if (neighborOmitted > 0) countText += ' · ' + neighborOmitted + ' omitted';
    var count = document.createElement('span');
    count.className = 'locality-google-map-legend__count';
    count.textContent = countText;
    legend.appendChild(count);
  }

  function showFailureForOwner() {
    if (!ownerRoot || !ownerRoot.isConnected || !ownerStatus) return;
    ownerStatus.hidden = false;
    ownerStatus.textContent = FAIL_MESSAGE;
    if (ownerControls) ownerControls.hidden = true;
    if (ownerLegend) ownerLegend.hidden = true;
    clearNeighborFeatures();
    if (dataLayer) {
      dataLayer.forEach(function (feature) { dataLayer.remove(feature); });
      dataLayer.setMap(null);
    }
    if (neighborLayer) neighborLayer.setMap(null);
    if (ownerFallback) ownerFallback.hidden = false;
    if (mapEl && mapEl.parentNode) mapEl.parentNode.removeChild(mapEl);
  }

  function loadSdk() {
    installAuthFailure();
    if (sdkFailed) return Promise.reject({ failed: true });
    if (sdkPromise) return sdkPromise;
    sdkPromise = getConfig().then(function (cfg) {
      if (!cfg || cfg.enabled !== true) return Promise.reject({ disabled: true });
      if (global.google && global.google.maps) return global.google.maps;
      return new Promise(function (resolve, reject) {
        var done = false;
        var timer = setTimeout(function () { finish(new Error('timeout')); }, LOAD_TIMEOUT);
        function finish(err) {
          if (done) return;
          done = true;
          clearTimeout(timer);
          if (err) { sdkFailed = true; reject(err); } else { resolve(global.google.maps); }
        }
        // Documented callback strategy: Google's async loader calls __localityMapsReady once.
        global.__localityMapsReady = function () { finish(); };
        var script = document.createElement('script');
        script.async = true;
        script.referrerPolicy = 'origin';
        script.onerror = function () { finish(new Error('network')); };
        script.src = SDK_JS + '?key=' + encodeURIComponent(cfg.key) + '&loading=async&callback=__localityMapsReady&v=weekly&language=ar&region=TN';
        document.head.appendChild(script);
      });
    }).catch(function (err) {
      if (!err || !err.disabled) sdkFailed = true;
      throw err;
    });
    return sdkPromise;
  }

  function ensureMap(center) {
    var gm = global.google && global.google.maps;
    if (!gm) throw new Error('maps-missing');
    if (!mapEl) { mapEl = document.createElement('div'); mapEl.className = 'locality-google-map-canvas'; }
    if (!map) {
      map = new gm.Map(mapEl, {
        center: center, zoom: 15, mapTypeId: gm.MapTypeId.ROADMAP,
        mapTypeControl: true,
        mapTypeControlOptions: { mapTypeIds: [gm.MapTypeId.ROADMAP, gm.MapTypeId.SATELLITE, gm.MapTypeId.HYBRID], style: gm.MapTypeControlStyle.DROPDOWN_MENU },
        zoomControl: true, scaleControl: true, streetViewControl: false, clickableIcons: false
      });
      neighborLayer = new gm.Data({ map: map });
      neighborLayer.setStyle({
        fillColor: '#1a73e8', fillOpacity: 0.12, strokeColor: '#1557b0', strokeOpacity: 0.75,
        strokeWeight: 2, zIndex: 1, clickable: true
      });
      neighborLayer.addListener('click', openNeighborInfo);
      neighborLayer.addListener('mouseover', openNeighborInfo);
      neighborInfoWindow = new gm.InfoWindow({ disableAutoPan: true });
      neighborInfoContent = document.createElement('div');
      neighborInfoContent.className = 'locality-google-map-info';
      neighborInfoWindow.setContent(neighborInfoContent);
      dataLayer = new gm.Data({ map: map });
      dataLayer.setStyle(function (feature) {
        var primary = !!(feature && typeof feature.getProperty === 'function' && feature.getProperty('primary'));
        return {
          fillColor: '#2e7d32', fillOpacity: 0.24, strokeColor: '#1b5e20', strokeOpacity: 0.95,
          strokeWeight: primary ? 3 : 2.5, zIndex: primary ? 3 : 2, clickable: false
        };
      });
      pinLayer = new gm.Data({ map: map });
      pinLayer.setStyle({
        icon: { path: gm.SymbolPath.CIRCLE, scale: 7, fillColor: '#d93025', fillOpacity: 1, strokeColor: '#ffffff', strokeWeight: 2 },
        zIndex: 10, title: 'Saved pin'
      });
      pinFeature = new gm.Data.Feature({ geometry: new gm.Data.Point(center) });
      pinLayer.add(pinFeature);
    }
    return map;
  }

  function extendBounds(bounds, geometry) {
    if (!geometry || geometry.type !== 'MultiPolygon' || !Array.isArray(geometry.coordinates)) return false;
    var has = false, polygons = geometry.coordinates;
    for (var i = 0; i < polygons.length; i++) {
      var rings = polygons[i];
      if (!Array.isArray(rings)) continue;
      for (var j = 0; j < rings.length; j++) {
        var ring = rings[j];
        if (!Array.isArray(ring)) continue;
        for (var k = 0; k < ring.length; k++) {
          var pos = ring[k];
          if (!Array.isArray(pos) || pos.length < 2) continue;
          var lng = Number(pos[0]), lat = Number(pos[1]);
          if (isFinite(lat) && isFinite(lng)) { bounds.extend({ lat: lat, lng: lng }); has = true; }
        }
      }
    }
    return has;
  }

  function fitMapToPayload(targetMap, payload, token) {
    if (!targetMap || (token !== undefined && token !== ownerToken)) return;
    var gm = global.google && global.google.maps;
    if (!gm) return;
    var pin = payload && payload.pin;
    var lat = pin && Number(pin.lat), lng = pin && Number(pin.lng);
    if (!isFinite(lat) || !isFinite(lng)) return;
    var bounds = new gm.LatLngBounds(), hasGeometry = false;
    var features = payload && Array.isArray(payload.features) ? payload.features : [];
    for (var i = 0; i < features.length; i++) {
      var feature = features[i];
      if (feature && feature.geometry) hasGeometry = extendBounds(bounds, feature.geometry) || hasGeometry;
    }
    bounds.extend({ lat: lat, lng: lng });
    if (!hasGeometry) { targetMap.setCenter({ lat: lat, lng: lng }); targetMap.setZoom(15); }
    else { targetMap.fitBounds(bounds, 40); }
  }

  function release(root) {
    if (!root || ownerRoot !== root) return;
    generation++;
    ownerToken = 0;
    closeNeighborInfo();
    if (dataLayer) {
      dataLayer.forEach(function (feature) { dataLayer.remove(feature); });
      dataLayer.setMap(null);
    }
    clearNeighborFeatures();
    if (neighborLayer) neighborLayer.setMap(null);
    if (mapEl && mapEl.parentNode) mapEl.parentNode.removeChild(mapEl);
    if (ownerShell && ownerShell.parentNode) ownerShell.parentNode.removeChild(ownerShell);
    if (ownerFallback) ownerFallback.hidden = false;
    ownerRoot = null; ownerShell = null; ownerStatus = null; ownerControls = null; ownerLegend = null; ownerFallback = null;
  }

  function mount(root, fallbackCanvas, loc, payload) {
    if (!root || !fallbackCanvas || !loc || !payload || !root.isConnected) return;
    if (ownerRoot) release(ownerRoot);
    closeNeighborInfo();
    if (payload.id == null || loc.id == null || String(payload.id) !== String(loc.id)) return;
    if (payload.fingerprint == null || loc.fingerprint == null || String(payload.fingerprint) !== String(loc.fingerprint)) return;
    if (!payload.pin || !isFinite(Number(payload.pin.lat)) || !isFinite(Number(payload.pin.lng))) return;

    var token = ++generation;
    ownerRoot = root;
    ownerToken = token;
    var shell = document.createElement('div');
    shell.className = 'locality-google-map-shell';
    shell.setAttribute('aria-label', loc.nameAr ? ('خريطة ' + loc.nameAr) : 'خريطة');
    var status = document.createElement('div');
    status.className = 'locality-google-map-status';
    status.textContent = 'Loading Google Maps…';
    var controls = document.createElement('div');
    controls.className = 'locality-google-map-controls';
    controls.hidden = true;
    var label = document.createElement('label');
    label.className = 'locality-google-map-toggle';
    var checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = showBoundaries;
    var labelText = document.createElement('span');
    labelText.textContent = 'Show app boundaries';
    label.appendChild(checkbox);
    label.appendChild(labelText);
    var neighborLabel = document.createElement('label');
    neighborLabel.className = 'locality-google-map-toggle';
    var neighborCheckbox = document.createElement('input');
    neighborCheckbox.type = 'checkbox';
    neighborCheckbox.checked = showNeighbors;
    var neighborLabelText = document.createElement('span');
    neighborLabelText.textContent = 'Show neighboring boundaries';
    neighborLabel.appendChild(neighborCheckbox);
    neighborLabel.appendChild(neighborLabelText);
    var legend = document.createElement('div');
    legend.className = 'locality-google-map-legend';
    legend.hidden = true;
    var fitButton = document.createElement('button');
    fitButton.type = 'button';
    fitButton.className = 'locality-google-map-fit';
    fitButton.textContent = 'Fit';
    controls.appendChild(label);
    controls.appendChild(neighborLabel);
    controls.appendChild(fitButton);
    shell.appendChild(status);
    shell.appendChild(controls);
    shell.appendChild(legend);
    fallbackCanvas.parentNode.insertBefore(shell, fallbackCanvas);
    ownerShell = shell; ownerStatus = status; ownerControls = controls; ownerLegend = legend; ownerFallback = fallbackCanvas;

    checkbox.addEventListener('change', function () {
      if (token !== ownerToken || !ownerRoot || !ownerRoot.isConnected) return;
      showBoundaries = checkbox.checked;
      closeNeighborInfo();
      if (dataLayer && map) dataLayer.setMap(showBoundaries ? map : null);
    });
    neighborCheckbox.addEventListener('change', function () {
      if (token !== ownerToken || !ownerRoot || !ownerRoot.isConnected) return;
      showNeighbors = neighborCheckbox.checked;
      closeNeighborInfo();
      if (neighborLayer && map) neighborLayer.setMap(showNeighbors ? map : null);
    });
    fitButton.addEventListener('click', function () {
      if (token !== ownerToken || !ownerRoot || !ownerRoot.isConnected) return;
      fitMapToPayload(map, payload, token);
    });

    loadSdk().then(function () {
      if (token !== ownerToken || ownerRoot !== root || !root.isConnected) return;
      if (sdkFailed || authFailed) throw new Error('sdk-failed');
      var pin = payload.pin;
      var center = new global.google.maps.LatLng(Number(pin.lat), Number(pin.lng));
      ensureMap(center);
      if (mapEl.parentNode) mapEl.parentNode.removeChild(mapEl);
      shell.insertBefore(mapEl, shell.firstChild);
      clearNeighborFeatures();
      dataLayer.forEach(function (feature) { dataLayer.remove(feature); });
      var features = Array.isArray(payload.features) ? payload.features : [];
      if (features.length) dataLayer.addGeoJson({ type: 'FeatureCollection', features: features });
      var neighbors = Array.isArray(payload.neighborFeatures) ? payload.neighborFeatures : [];
      if (neighbors.length) neighborLayer.addGeoJson({ type: 'FeatureCollection', features: neighbors });
      neighborLayer.setMap(showNeighbors ? map : null);
      pinFeature.setGeometry(new global.google.maps.Data.Point(center));
      pinLayer.setMap(map);
      dataLayer.setMap(showBoundaries ? map : null);
      var neighborTotal = typeof payload.neighborTotal === 'number' && isFinite(payload.neighborTotal)
        ? payload.neighborTotal : neighbors.length;
      var neighborTruncated = payload.neighborTruncated === true;
      var neighborOmitted = typeof payload.neighborOmitted === 'number' && isFinite(payload.neighborOmitted)
        ? payload.neighborOmitted : 0;
      updateLegend(legend, neighbors.length, neighborTotal, neighborTruncated, neighborOmitted);
      legend.hidden = false;
      status.hidden = true;
      status.textContent = '';
      controls.hidden = false;
      fallbackCanvas.hidden = true;
      global.google.maps.event.trigger(map, 'resize');
      fitMapToPayload(map, payload, token);
    }).catch(function (err) {
      if (token !== ownerToken || ownerRoot !== root || !root.isConnected) return;
      if (err && err.disabled) { release(root); return; }
      closeNeighborInfo();
      if (dataLayer) {
        dataLayer.forEach(function (feature) { dataLayer.remove(feature); });
        dataLayer.setMap(null);
      }
      clearNeighborFeatures();
      if (neighborLayer) neighborLayer.setMap(null);
      if (legend) legend.hidden = true;
      showFailureForOwner();
    });
  }

  global.LocalityGoogleMap = { mount: mount, release: release };
})(window);
