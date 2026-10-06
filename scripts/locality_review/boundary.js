(function () {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';
  var MIN_ZOOM = 0.5;
  var MAX_ZOOM = 40;
  var ZOOM_STEP = 1.6;
  var METERS_PER_UNIT = 111195;
  var DEFAULT_SPAN = 0.012;
  var PALETTE = ['#2f6b4f', '#4f8a63', '#6f8f3f', '#2d7d6b', '#577a35', '#3d7c74'];
  var NEIGHBOR_COLOR = '#1a73e8';
  var mounted = typeof WeakMap === 'function' ? new WeakMap() : null;
  var seq = 0;

  function createEl(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function createSvg(tag, attrs) {
    var node = document.createElementNS(SVG_NS, tag);
    if (attrs) {
      for (var key in attrs) {
        if (Object.prototype.hasOwnProperty.call(attrs, key)) {
          node.setAttribute(key, attrs[key]);
        }
      }
    }
    return node;
  }

  function strictNumber(value) {
    return typeof value === 'number' && isFinite(value) ? value : null;
  }

  function coordinateNumber(value) {
    if (typeof value === 'number' && isFinite(value)) return value;
    if (typeof value === 'string' && value.trim() !== '') {
      var parsed = Number(value);
      if (isFinite(parsed)) return parsed;
    }
    return null;
  }

  function mount(container, loc) {
    if (!container || typeof container.appendChild !== 'function' || !loc) return null;

    var token = {};
    if (mounted) mounted.set(container, token);

    var children = container.children;
    for (var i = children.length - 1; i >= 0; i--) {
      var child = children[i];
      if (child && child.classList && child.classList.contains('boundary-preview')) {
        container.removeChild(child);
      }
    }

    var titleId = 'boundary-preview-title-' + (++seq);

    var root = createEl('section', 'boundary-preview');
    root.setAttribute('aria-labelledby', titleId);

    var head = createEl('div', 'boundary-preview__head');
    var title = createEl('h3', 'boundary-preview__title', 'App boundary');
    title.id = titleId;
    var subtitle = createEl('p', 'boundary-preview__subtitle', 'This stored outline is for checking only; it is not proof that the boundary is correct.');
    head.appendChild(title);
    head.appendChild(subtitle);

    var body = createEl('div', 'boundary-preview__body');
    var status = createEl('div', 'boundary-preview__status', 'Loading boundary…');
    status.setAttribute('role', 'status');
    status.setAttribute('aria-live', 'polite');

    var canvas = createEl('div', 'boundary-preview__canvas');
    canvas.hidden = true;

    var svg = createSvg('svg', {
      'class': 'boundary-preview__map',
      'role': 'img',
      'tabindex': '0',
      'aria-label': 'Boundary preview map. Drag to pan; use Zoom in, Zoom out, or Fit to adjust.'
    });
    var svgTitle = createSvg('title');
    svgTitle.textContent = 'App boundary preview';
    svg.appendChild(svgTitle);

    var world = createSvg('g', { 'class': 'bp-world' });
    var pinLayer = createSvg('g', { 'class': 'bp-pin-layer' });
    var hud = createSvg('g', { 'class': 'bp-hud' });

    var pinGroup = createSvg('g', { 'class': 'bp-pin', 'display': 'none' });
    var pinShape = createSvg('path', {
      'class': 'bp-pin__shape',
      'd': 'M0,-14 C6.2,-14 10,-9.8 10,-4.5 C10,2 0,14 0,14 C0,14 -10,2 -10,-4.5 C-10,-9.8 -6.2,-14 0,-14 Z'
    });
    var pinDot = createSvg('circle', { 'class': 'bp-pin__dot', 'cx': '0', 'cy': '-4.5', 'r': '3.2' });
    pinGroup.appendChild(pinShape);
    pinGroup.appendChild(pinDot);
    pinLayer.appendChild(pinGroup);

    var north = createSvg('g', { 'class': 'bp-north', 'transform': 'translate(20, 30)', 'aria-hidden': 'true' });
    north.appendChild(createSvg('path', { 'class': 'bp-north__needle', 'd': 'M0,-11 L4.5,5 L0,1.5 L-4.5,5 Z' }));
    var northLabel = createSvg('text', { 'class': 'bp-north__label', 'x': '0', 'y': '-14', 'text-anchor': 'middle' });
    northLabel.textContent = 'N';
    north.appendChild(northLabel);

    var scaleGroup = createSvg('g', { 'class': 'bp-scale', 'transform': 'translate(16, 280)', 'aria-hidden': 'true' });
    var scaleBg = createSvg('rect', { 'class': 'bp-scale__bg', 'x': '-8', 'y': '-16', 'width': '100', 'height': '24', 'rx': '4' });
    var scaleLine = createSvg('line', { 'class': 'bp-scale__line', 'x1': '0', 'y1': '0', 'x2': '80', 'y2': '0' });
    var scaleTickA = createSvg('line', { 'class': 'bp-scale__tick', 'x1': '0', 'y1': '-4', 'x2': '0', 'y2': '4' });
    var scaleTickB = createSvg('line', { 'class': 'bp-scale__tick', 'x1': '80', 'y1': '-4', 'x2': '80', 'y2': '4' });
    var scaleLabel = createSvg('text', { 'class': 'bp-scale__label', 'x': '40', 'y': '-5', 'text-anchor': 'middle' });
    scaleGroup.appendChild(scaleBg);
    scaleGroup.appendChild(scaleLine);
    scaleGroup.appendChild(scaleTickA);
    scaleGroup.appendChild(scaleTickB);
    scaleGroup.appendChild(scaleLabel);

    hud.appendChild(north);
    hud.appendChild(scaleGroup);

    svg.appendChild(world);
    svg.appendChild(pinLayer);
    svg.appendChild(hud);

    var tools = createEl('div', 'boundary-preview__tools');
    var zoomInBtn = createEl('button', 'boundary-preview__tool', '+');
    zoomInBtn.type = 'button';
    zoomInBtn.setAttribute('aria-label', 'Zoom in');
    zoomInBtn.title = 'Zoom in';
    var zoomOutBtn = createEl('button', 'boundary-preview__tool', '−');
    zoomOutBtn.type = 'button';
    zoomOutBtn.setAttribute('aria-label', 'Zoom out');
    zoomOutBtn.title = 'Zoom out';
    var fitBtn = createEl('button', 'boundary-preview__tool', 'Fit');
    fitBtn.type = 'button';
    fitBtn.setAttribute('aria-label', 'Fit boundary to view');
    fitBtn.title = 'Fit boundary to view';
    tools.appendChild(zoomInBtn);
    tools.appendChild(zoomOutBtn);
    tools.appendChild(fitBtn);
    var offlineNeighborToggle = createEl('label', 'boundary-preview__legend-toggle');
    var offlineNeighborCheckbox = document.createElement('input');
    offlineNeighborCheckbox.type = 'checkbox';
    offlineNeighborCheckbox.checked = true;
    offlineNeighborToggle.appendChild(offlineNeighborCheckbox);
    offlineNeighborToggle.appendChild(createEl('span', null, 'Show neighboring boundaries'));
    tools.appendChild(offlineNeighborToggle);
    offlineNeighborCheckbox.addEventListener('change', function () {
      if (!isCurrent()) return;
      showNeighbors = offlineNeighborCheckbox.checked;
      drawFeatures();
    });

    var legend = createEl('div', 'boundary-preview__legend');
    legend.hidden = true;
    legend.setAttribute('role', 'group');
    legend.setAttribute('aria-label', 'Boundary preview legend');

    canvas.appendChild(svg);
    canvas.appendChild(tools);
    body.appendChild(status);
    body.appendChild(canvas);
    body.appendChild(legend);
    root.appendChild(head);
    root.appendChild(body);
    container.appendChild(root);

    var destroyed = false;
    var W = 600;
    var H = 300;
    var baseK = 1;
    var zoom = 1;
    var panX = 0;
    var panY = 0;
    var bounds = null;
    var spanX = DEFAULT_SPAN;
    var spanY = DEFAULT_SPAN;
    var pinWorld = null;
    var pinData = null;
    var features = [];
    var neighbors = [];
    var showNeighbors = true;
    var dragging = null;
    var loadSeq = 0;
    var resizeObserver = null;
    var windowResizeHandler = null;

    function isActiveInstance() {
      if (destroyed) return false;
      if (mounted && mounted.get(container) !== token) return false;
      return true;
    }

    function isCurrent() {
      if (!isActiveInstance()) return false;
      if (container && container.isConnected === false) return false;
      if (root && root.isConnected === false) return false;
      return true;
    }

    function measure() {
      var rect = svg.getBoundingClientRect();
      var w = Math.round(rect.width);
      var h = Math.round(rect.height);
      if (!(w > 0) && svg.clientWidth) w = svg.clientWidth;
      if (!(h > 0) && svg.clientHeight) h = svg.clientHeight;
      if (!(w > 40)) w = 600;
      if (!(h > 40)) h = 300;
      W = w;
      H = h;
      svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    }

    function computeFit() {
      if (!bounds) {
        bounds = {
          minX: -DEFAULT_SPAN / 2,
          maxX: DEFAULT_SPAN / 2,
          minY: -DEFAULT_SPAN / 2,
          maxY: DEFAULT_SPAN / 2
        };
      }
      var pad = Math.max(18, Math.min(34, Math.min(W, H) * 0.08));
      var availW = Math.max(48, W - pad * 2);
      var availH = Math.max(48, H - pad * 2);
      spanX = bounds.maxX - bounds.minX;
      spanY = bounds.maxY - bounds.minY;
      if (!(spanX > 0)) spanX = DEFAULT_SPAN;
      if (!(spanY > 0)) spanY = DEFAULT_SPAN;
      baseK = Math.min(availW / spanX, availH / spanY);
      if (!isFinite(baseK) || baseK <= 0) baseK = 1;
      if (baseK < 0.000001) baseK = 0.000001;
      if (baseK > 10000000) baseK = 10000000;
    }

    function clampPan(k) {
      var halfX = W / 2 + (Math.max(spanX, 0.000001) * k) / 2;
      var halfY = H / 2 + (Math.max(spanY, 0.000001) * k) / 2;
      if (panX > halfX) panX = halfX;
      if (panX < -halfX) panX = -halfX;
      if (panY > halfY) panY = halfY;
      if (panY < -halfY) panY = -halfY;
    }

    function niceNumber(value) {
      if (!(value > 0)) return 1;
      var exponent = Math.floor(Math.log(value) / Math.LN10);
      var base = Math.pow(10, exponent);
      var fraction = value / base;
      var multiplier = fraction >= 5 ? 5 : fraction >= 2 ? 2 : 1;
      return multiplier * base;
    }

    function formatDistance(meters) {
      if (meters >= 1000) {
        var km = meters / 1000;
        return (km >= 10 ? Math.round(km) : Math.round(km * 10) / 10) + ' km';
      }
      if (meters >= 10) return Math.round(meters) + ' m';
      if (meters >= 1) return Math.round(meters * 10) / 10 + ' m';
      return Math.round(meters * 100) / 100 + ' m';
    }

    function updateScale(k) {
      var metersPerPixel = METERS_PER_UNIT / k;
      if (!isFinite(metersPerPixel) || metersPerPixel <= 0) metersPerPixel = 1;
      var targetPx = Math.max(56, Math.min(96, W * 0.22));
      var niceMeters = niceNumber(targetPx * metersPerPixel);
      var px = niceMeters / metersPerPixel;
      var maxPx = Math.max(36, W - 56);
      if (px > maxPx) {
        niceMeters = niceNumber(maxPx * metersPerPixel);
        px = niceMeters / metersPerPixel;
      }
      if (!isFinite(px) || px <= 0) {
        px = 56;
        niceMeters = px * metersPerPixel;
      }
      scaleLine.setAttribute('x2', String(px));
      scaleTickB.setAttribute('x1', String(px));
      scaleTickB.setAttribute('x2', String(px));
      scaleLabel.setAttribute('x', String(px / 2));
      scaleLabel.textContent = formatDistance(niceMeters);
      scaleBg.setAttribute('width', String(px + 16));
      scaleGroup.setAttribute('transform', 'translate(16 ' + (H - 16) + ')');
    }

    function render() {
      if (!W || !H || !bounds) return;
      var k = baseK * zoom;
      var cx = (bounds.minX + bounds.maxX) / 2;
      var cy = (bounds.minY + bounds.maxY) / 2;
      var tx = W / 2 - cx * k + panX;
      var ty = H / 2 - cy * k + panY;
      world.setAttribute('transform', 'translate(' + tx + ' ' + ty + ') scale(' + k + ')');
      if (pinWorld) {
        var px = pinWorld.x * k + tx;
        var py = pinWorld.y * k + ty;
        if (px > -32 && px < W + 32 && py > -40 && py < H + 40) {
          pinGroup.removeAttribute('display');
          pinGroup.setAttribute('transform', 'translate(' + px + ' ' + (py - 14) + ')');
        } else {
          pinGroup.setAttribute('display', 'none');
        }
      } else {
        pinGroup.setAttribute('display', 'none');
      }
      updateScale(k);
    }

    function fmt(value) {
      return String(value);
    }

    function ringToPath(ring) {
      var parts = [];
      for (var i = 0; i < ring.length; i++) {
        var point = ring[i];
        parts.push((i === 0 ? 'M' : 'L') + fmt(point.x) + ' ' + fmt(point.y));
      }
      parts.push('Z');
      return parts.join(' ');
    }

    function extractPolygons(geometry, project) {
      if (!geometry || typeof geometry !== 'object') return [];
      var type = geometry.type;
      var coordinates = geometry.coordinates;
      var polygonsIn = null;
      if (type === 'MultiPolygon' && Array.isArray(coordinates)) polygonsIn = coordinates;
      else if (type === 'Polygon' && Array.isArray(coordinates)) polygonsIn = [coordinates];
      if (!polygonsIn) return [];

      var polygonsOut = [];
      for (var i = 0; i < polygonsIn.length; i++) {
        var polygon = polygonsIn[i];
        if (!Array.isArray(polygon)) continue;
        var rings = [];
        for (var j = 0; j < polygon.length; j++) {
          var ring = polygon[j];
          if (!Array.isArray(ring) || ring.length < 4) {
            rings.push(null);
            continue;
          }
          var points = [];
          var valid = true;
          for (var k = 0; k < ring.length; k++) {
            var position = ring[k];
            if (!Array.isArray(position) || position.length < 2) {
              valid = false;
              break;
            }
            var lng = strictNumber(position[0]);
            var lat = strictNumber(position[1]);
            if (lng === null || lat === null || lng < -180 || lng > 180 || lat < -90 || lat > 90) {
              valid = false;
              break;
            }
            points.push(project(lng, lat));
          }
          rings.push(valid && points.length >= 4 ? points : null);
        }
        if (!rings.length || !rings[0]) continue;
        var usableRings = [];
        for (var r = 0; r < rings.length; r++) {
          if (rings[r]) usableRings.push(rings[r]);
        }
        if (usableRings.length) polygonsOut.push(usableRings);
      }
      return polygonsOut;
    }

    function drawFeatures() {
      while (world.firstChild) world.removeChild(world.firstChild);
      if (showNeighbors) {
        for (var n = 0; n < neighbors.length; n++) {
          var neighbor = neighbors[n];
          var neighborGroup = createSvg('g', { 'class': 'bp-feature bp-feature--neighbor' });
          var neighborTitle = createSvg('title');
          neighborTitle.textContent = neighbor.name || neighbor.id || ('Neighboring outline ' + (n + 1));
          neighborGroup.appendChild(neighborTitle);
          for (var np = 0; np < neighbor.polygons.length; np++) {
            var neighborPolygon = neighbor.polygons[np];
            var nd = '';
            for (var nr = 0; nr < neighborPolygon.length; nr++) {
              if (nd) nd += ' ';
              nd += ringToPath(neighborPolygon[nr]);
            }
            var neighborPath = createSvg('path', {
              'd': nd,
              'class': 'bp-path bp-path--neighbor',
              'fill-rule': 'evenodd',
              'clip-rule': 'evenodd',
              'vector-effect': 'non-scaling-stroke'
            });
            neighborPath.setAttribute('fill', NEIGHBOR_COLOR);
            neighborPath.setAttribute('fill-opacity', '0.10');
            neighborPath.setAttribute('stroke', NEIGHBOR_COLOR);
            neighborPath.setAttribute('stroke-opacity', '0.75');
            neighborGroup.appendChild(neighborPath);
          }
          world.appendChild(neighborGroup);
        }
      }
      for (var i = 0; i < features.length; i++) {
        var feature = features[i];
        var color = PALETTE[i % PALETTE.length];
        var group = createSvg('g', { 'class': 'bp-feature' + (feature.primary ? ' bp-feature--primary' : '') });
        var featureTitle = createSvg('title');
        featureTitle.textContent = feature.name || feature.id || ('Outline ' + (i + 1));
        group.appendChild(featureTitle);
        for (var p = 0; p < feature.polygons.length; p++) {
          var polygon = feature.polygons[p];
          var d = '';
          for (var r = 0; r < polygon.length; r++) {
            if (d) d += ' ';
            d += ringToPath(polygon[r]);
          }
          var path = createSvg('path', {
            'd': d,
            'class': 'bp-path' + (feature.primary ? ' bp-path--primary' : ''),
            'fill-rule': 'evenodd',
            'clip-rule': 'evenodd',
            'vector-effect': 'non-scaling-stroke'
          });
          path.setAttribute('fill', color);
          path.setAttribute('fill-opacity', feature.primary ? '0.26' : '0.16');
          path.setAttribute('stroke', color);
          group.appendChild(path);
        }
        world.appendChild(group);
      }
    }

    function createPinSwatch() {
      var swatch = createEl('span', 'boundary-preview__swatch boundary-preview__swatch--pin');
      swatch.setAttribute('aria-hidden', 'true');
      return swatch;
    }

    function formatPinCoords(pin) {
      return Math.abs(pin.lat).toFixed(5) + '° ' + (pin.lat >= 0 ? 'N' : 'S') + ', ' +
        Math.abs(pin.lng).toFixed(5) + '° ' + (pin.lng >= 0 ? 'E' : 'W');
    }

    function buildLegend() {
      while (legend.firstChild) legend.removeChild(legend.firstChild);

      if (features.length === 0 && neighbors.length === 0) {
        if (!pinData) {
          legend.hidden = true;
          return;
        }
        var pinOnly = createEl('div', 'boundary-preview__legend-item');
        pinOnly.appendChild(createPinSwatch());
        pinOnly.appendChild(createEl('span', 'boundary-preview__legend-name', 'Saved pin'));
        pinOnly.appendChild(createEl('span', 'boundary-preview__legend-coords', formatPinCoords(pinData)));
        legend.appendChild(pinOnly);
        legend.hidden = false;
        return;
      }

      if (features.length > 0) {
        legend.appendChild(createEl('div', 'boundary-preview__legend-title',
          features.length + ' selected/grouped outline' + (features.length === 1 ? '' : 's')));

        for (var i = 0; i < features.length; i++) {
          var feature = features[i];
          var item = createEl('div', 'boundary-preview__legend-item');
          var swatch = createEl('span', 'boundary-preview__swatch' + (feature.primary ? '' : ' is-dashed'));
          swatch.style.setProperty('--bp-swatch-color', PALETTE[i % PALETTE.length]);
          item.appendChild(swatch);
          var nameText = feature.name || feature.id || ('Outline ' + (i + 1));
          var name = createEl('span', 'boundary-preview__legend-name', nameText);
          name.title = feature.id ? (nameText + ' — ' + feature.id) : nameText;
          item.appendChild(name);
          if (feature.primary) {
            item.appendChild(createEl('span', 'boundary-preview__legend-tag', 'selected'));
          } else {
            item.appendChild(createEl('span', 'boundary-preview__legend-tag', 'grouped'));
          }
          legend.appendChild(item);
        }
      }

      if (neighbors.length > 0) {
        legend.appendChild(createEl('div', 'boundary-preview__legend-title',
          'Neighboring outlines (' + neighbors.length + ')'));
        var neighborItem = createEl('div', 'boundary-preview__legend-item');
        var neighborSwatch = createEl('span', 'boundary-preview__swatch');
        neighborSwatch.style.setProperty('--bp-swatch-color', NEIGHBOR_COLOR);
        neighborItem.appendChild(neighborSwatch);
        neighborItem.appendChild(createEl('span', 'boundary-preview__legend-name', 'Blue neighboring outlines'));
        legend.appendChild(neighborItem);
        var names = createEl('details', 'boundary-preview__neighbor-names');
        names.appendChild(createEl('summary', null, 'Neighboring place names'));
        var listedNames = Object.create(null);
        for (var n = 0; n < neighbors.length; n++) {
          var neighborName = neighbors[n].name;
          if (neighborName && !listedNames[neighborName]) {
            names.appendChild(createEl('div', 'boundary-preview__legend-name', neighborName));
            listedNames[neighborName] = true;
          }
        }
        legend.appendChild(names);
      }

      if (pinData) {
        var pinItem = createEl('div', 'boundary-preview__legend-item');
        pinItem.appendChild(createPinSwatch());
        pinItem.appendChild(createEl('span', 'boundary-preview__legend-name', 'Saved pin'));
        pinItem.appendChild(createEl('span', 'boundary-preview__legend-coords', formatPinCoords(pinData)));
        legend.appendChild(pinItem);
      }

      legend.hidden = false;
    }

    function clearVisuals() {
      while (world.firstChild) world.removeChild(world.firstChild);
      while (legend.firstChild) legend.removeChild(legend.firstChild);
      pinGroup.setAttribute('display', 'none');
      pinData = null;
      pinWorld = null;
      features = [];
      neighbors = [];
      bounds = null;
      legend.hidden = true;
      canvas.hidden = true;
    }

    function showLoading() {
      clearVisuals();
      status.hidden = false;
      status.className = 'boundary-preview__status';
      status.setAttribute('role', 'status');
      status.textContent = 'Loading boundary…';
    }

    function showError(message) {
      clearVisuals();
      status.hidden = false;
      status.className = 'boundary-preview__status is-error';
      status.setAttribute('role', 'alert');
      status.textContent = '';
      var text = createEl('span', 'boundary-preview__status-text', message || 'The boundary could not be loaded.');
      var retry = createEl('button', 'boundary-preview__retry', 'Retry');
      retry.type = 'button';
      retry.setAttribute('aria-label', 'Retry loading boundary');
      retry.addEventListener('click', function () {
        if (!isActiveInstance()) return;
        load();
      });
      status.appendChild(text);
      status.appendChild(retry);
    }

    function readPin(value) {
      if (!value || typeof value !== 'object') return null;
      var lat = coordinateNumber(value.lat);
      var lng = coordinateNumber(value.lng);
      if (lat === null || lng === null) return null;
      if (lat < -90 || lat > 90) return null;
      if (lng < -180 || lng > 180) return null;
      return { lat: lat, lng: lng };
    }

    function payloadMatches(payload) {
      if (!payload || typeof payload !== 'object') return false;
      if (String(payload.id) !== String(loc.id)) return false;
      if (loc.fingerprint !== undefined && loc.fingerprint !== null) {
        if (String(payload.fingerprint) !== String(loc.fingerprint)) return false;
      }
      return true;
    }

    function applyPayload(payload) {
      pinData = readPin(payload.pin);
      if (!pinData) pinData = readPin({ lat: loc.lat, lng: loc.lng });

      var lat0 = coordinateNumber(loc.lat);
      if (lat0 === null) lat0 = pinData ? pinData.lat : 0;
      var lon0 = coordinateNumber(loc.lng);
      if (lon0 === null) lon0 = pinData ? pinData.lng : 0;
      var cosLat = Math.cos(lat0 * Math.PI / 180);
      if (!isFinite(cosLat) || cosLat < 0.000001) cosLat = 0.000001;

      function project(lng, lat) {
        return { x: (lng - lon0) * cosLat, y: lat0 - lat };
      }

      function buildOutlines(rawFeatures, isNeighbor) {
        var built = [];
        if (!Array.isArray(rawFeatures)) return built;
        for (var i = 0; i < rawFeatures.length; i++) {
          var feature = rawFeatures[i];
          if (!feature || typeof feature !== 'object') continue;
          var props = feature.properties && typeof feature.properties === 'object' ? feature.properties : {};
          var featureId = '';
          if (typeof feature.id === 'string' || typeof feature.id === 'number') featureId = String(feature.id);
          else if (typeof props.id === 'string' || typeof props.id === 'number') featureId = String(props.id);
          var featureName = typeof props.name === 'string' ? props.name : '';
          var primary = !isNeighbor && (props.primary === true || (featureId !== '' && featureId === String(loc.id)));
          var polygons = extractPolygons(feature.geometry, project);
          if (!polygons.length) continue;
          built.push({ id: featureId, name: featureName, primary: primary, neighbor: isNeighbor, polygons: polygons });
        }
        return built;
      }

      var rawFeatures = Array.isArray(payload.features) ? payload.features : [];
      var rawNeighbors = Array.isArray(payload.neighborFeatures) ? payload.neighborFeatures : [];
      var built = buildOutlines(rawFeatures, false);
      var builtNeighbors = buildOutlines(rawNeighbors, true);

      if (rawFeatures.length > 0 && built.length === 0) {
        showError('The stored outline could not be displayed.');
        return;
      }

      features = built;
      neighbors = builtNeighbors;

      var box = null;
      function extend(x, y) {
        if (!box) {
          box = { minX: x, maxX: x, minY: y, maxY: y };
        } else {
          if (x < box.minX) box.minX = x;
          if (x > box.maxX) box.maxX = x;
          if (y < box.minY) box.minY = y;
          if (y > box.maxY) box.maxY = y;
        }
      }

      for (var f = 0; f < features.length; f++) {
        for (var p = 0; p < features[f].polygons.length; p++) {
          for (var r = 0; r < features[f].polygons[p].length; r++) {
            var ring = features[f].polygons[p][r];
            for (var v = 0; v < ring.length; v++) {
              extend(ring[v].x, ring[v].y);
            }
          }
        }
      }

      pinWorld = pinData ? project(pinData.lng, pinData.lat) : null;
      if (pinWorld) extend(pinWorld.x, pinWorld.y);

      if (!box) {
        box = {
          minX: -DEFAULT_SPAN / 2,
          maxX: DEFAULT_SPAN / 2,
          minY: -DEFAULT_SPAN / 2,
          maxY: DEFAULT_SPAN / 2
        };
      }
      bounds = box;

      drawFeatures();
      buildLegend();

      canvas.hidden = false;
      if (features.length === 0) {
        status.hidden = false;
        status.className = 'boundary-preview__status is-note';
        status.setAttribute('role', 'status');
        status.textContent = 'No polygon is stored for this place.';
      } else {
        status.hidden = !payload.groupedOutlinesOnly;
        status.className = 'boundary-preview__status';
        status.textContent = payload.groupedOutlinesOnly
          ? 'This entry has no boundary of its own. The outlines below belong to places grouped with it.' : '';
      }

      measure();
      computeFit();
      zoom = 1;
      panX = 0;
      panY = 0;
      render();
      if (window.LocalityGoogleMap) {
        window.LocalityGoogleMap.mount(root, canvas, loc, payload);
      }
    }

    function load() {
      if (!isActiveInstance()) return;
      var seqId = ++loadSeq;
      showLoading();

      var url = '/api/boundary?id=' + encodeURIComponent(String(loc.id));
      var request;
      try {
        request = fetch(url, {
          cache: 'no-store',
          credentials: 'same-origin',
          headers: { 'Accept': 'application/json' }
        });
      } catch (error) {
        if (isCurrent() && seqId === loadSeq) showError('The boundary could not be loaded.');
        return;
      }

      Promise.resolve(request).then(function (response) {
        return response.text().then(function (text) {
          var data = null;
          if (text) {
            try { data = JSON.parse(text); } catch (error) { data = null; }
          }
          if (!response.ok) {
            var message = data && typeof data.message === 'string' && data.message
              ? data.message
              : 'The boundary could not be loaded (HTTP ' + response.status + ').';
            throw new Error(message);
          }
          if (!data || typeof data !== 'object') {
            throw new Error('The boundary response was not readable.');
          }
          return data;
        });
      }).then(function (payload) {
        if (!isCurrent() || seqId !== loadSeq) return;
        if (!payloadMatches(payload)) {
          showError('The boundary response did not match the selected place.');
          return;
        }
        applyPayload(payload);
      }).catch(function (error) {
        if (!isCurrent() || seqId !== loadSeq) return;
        var message = error && typeof error.message === 'string' && error.message
          ? error.message
          : 'The boundary could not be loaded.';
        showError(message);
      });
    }

    function setZoom(next) {
      var clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, next));
      if (clamped === zoom) return;
      zoom = clamped;
      clampPan(baseK * zoom);
      render();
    }

    zoomInBtn.addEventListener('click', function () { setZoom(zoom * ZOOM_STEP); });
    zoomOutBtn.addEventListener('click', function () { setZoom(zoom / ZOOM_STEP); });
    fitBtn.addEventListener('click', function () {
      zoom = 1;
      panX = 0;
      panY = 0;
      computeFit();
      render();
    });

    svg.addEventListener('pointerdown', function (event) {
      if (!isCurrent()) return;
      if (event.pointerType === 'mouse' && event.button !== 0) return;
      if (event.isPrimary === false) return;
      dragging = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        startPanX: panX,
        startPanY: panY
      };
      try { svg.setPointerCapture(event.pointerId); } catch (error) { }
      svg.classList.add('is-dragging');
      if (event.cancelable) event.preventDefault();
    });

    svg.addEventListener('pointermove', function (event) {
      if (!dragging || event.pointerId !== dragging.pointerId) return;
      panX = dragging.startPanX + (event.clientX - dragging.startX);
      panY = dragging.startPanY + (event.clientY - dragging.startY);
      clampPan(baseK * zoom);
      render();
      if (event.cancelable) event.preventDefault();
    });

    function endDrag(event) {
      if (!dragging) return;
      if (event && event.pointerId !== undefined && event.pointerId !== dragging.pointerId) return;
      var pointerId = dragging.pointerId;
      dragging = null;
      svg.classList.remove('is-dragging');
      try {
        if (svg.hasPointerCapture && svg.hasPointerCapture(pointerId)) {
          svg.releasePointerCapture(pointerId);
        }
      } catch (error) { }
    }

    svg.addEventListener('pointerup', endDrag);
    svg.addEventListener('pointercancel', endDrag);
    svg.addEventListener('lostpointercapture', endDrag);

    svg.addEventListener('keydown', function (event) {
      if (!isCurrent()) return;
      var key = event.key;
      if (key === '+' || key === '=') {
        event.preventDefault();
        setZoom(zoom * ZOOM_STEP);
      } else if (key === '-' || key === '_') {
        event.preventDefault();
        setZoom(zoom / ZOOM_STEP);
      } else if (key === '0') {
        event.preventDefault();
        zoom = 1;
        panX = 0;
        panY = 0;
        computeFit();
        render();
      } else if (key === 'ArrowLeft' || key === 'ArrowRight' || key === 'ArrowUp' || key === 'ArrowDown') {
        event.preventDefault();
        var step = 40;
        if (key === 'ArrowLeft') panX += step;
        else if (key === 'ArrowRight') panX -= step;
        else if (key === 'ArrowUp') panY += step;
        else panY -= step;
        clampPan(baseK * zoom);
        render();
      }
    });

    if (typeof ResizeObserver === 'function') {
      resizeObserver = new ResizeObserver(function () {
        if (!isActiveInstance()) {
          if (resizeObserver) resizeObserver.disconnect();
          return;
        }
        if (canvas.hidden) return;
        measure();
        computeFit();
        clampPan(baseK * zoom);
        render();
      });
      resizeObserver.observe(svg);
    } else if (typeof window !== 'undefined' && window.addEventListener) {
      windowResizeHandler = function () {
        if (!isActiveInstance()) {
          window.removeEventListener('resize', windowResizeHandler);
          windowResizeHandler = null;
          return;
        }
        if (canvas.hidden) return;
        measure();
        computeFit();
        clampPan(baseK * zoom);
        render();
      };
      window.addEventListener('resize', windowResizeHandler);
    }

    root.__localityBoundaryPreview = {
      destroy: function () {
        destroyed = true;
        if (window.LocalityGoogleMap) window.LocalityGoogleMap.release(root);
        if (resizeObserver) {
          resizeObserver.disconnect();
          resizeObserver = null;
        }
        if (windowResizeHandler && typeof window !== 'undefined') {
          window.removeEventListener('resize', windowResizeHandler);
          windowResizeHandler = null;
        }
        if (root.parentNode) root.parentNode.removeChild(root);
      }
    };

    load();
    return root;
  }

  if (typeof window !== 'undefined') {
    window.LocalityBoundaryPreview = window.LocalityBoundaryPreview || {};
    window.LocalityBoundaryPreview.mount = mount;
  }
})();
