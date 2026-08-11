import {readFile} from 'node:fs/promises';

const application = await readFile(process.argv[2], 'utf8');
const stylesheet = await readFile(process.argv[3], 'utf8');
let failed = false;

function declaration(prefix) {
  const start = application.indexOf(prefix);
  if (start < 0) return null;
  const body = application.indexOf('{', start);
  let depth = 0;
  for (let index = body; index < application.length; index++) {
    if (application[index] === '{') depth++;
    else if (application[index] === '}' && --depth === 0) {
      return application.slice(start, index + 1);
    }
  }
  throw new Error(`unterminated ${prefix}`);
}

function expect(name, condition, detail) {
  if (!condition) {
    console.error(`${name}: ${detail}`);
    failed = true;
  } else {
    console.log(`${name}: passed`);
  }
}

const playerRule = stylesheet.match(/\.web-player\{([^}]*)\}/)?.[1] ?? '';
expect(
  'square player avatar',
  playerRule.includes('width:28px')
    && playerRule.includes('height:28px')
    && !playerRule.includes('border-radius:50%'),
  `unexpected rule ${playerRule}`
);

class FakeElement {
  constructor(tag) {
    this.tag = tag;
    this.children = [];
    this.listeners = new Map();
    this.classList = {add() {}, toggle() {}};
    this.removed = false;
    this.src = '';
  }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  append(...children) { this.children.push(...children); }
  remove() { this.removed = true; }
  dispatch(type) { this.listeners.get(type)?.(); }
}

const iconSource = declaration('function playerIcon(');
if (iconSource) {
  function fallbackFor(random) {
    const document = {createElement: tag => new FakeElement(tag)};
    const L = {divIcon: options => options};
    const create = new Function('document', 'L', 'Math', `${iconSource}; return playerIcon;`)(
      document, L, {random: () => random}
    );
    const icon = create({id: 'offline-player', name: 'Offline', translucent: false});
    const image = icon.html.children.find(child => child.tag === 'img');
    image.dispatch('error');
    return image;
  }
  const first = fallbackFor(0.1);
  const second = fallbackFor(0.9);
  const defaults = [first.src, second.src];
  expect(
    'missing skin keeps an image avatar',
    !first.removed && !second.removed,
    'the failed image was removed'
  );
  expect(
    'missing skin selects Steve and Alex',
    defaults.some(src => src.toLowerCase().includes('steve'))
      && defaults.some(src => src.toLowerCase().includes('alex')),
    `fallback sources were ${defaults.join(', ')}`
  );
} else {
  expect('player icon function', false, 'function playerIcon is missing');
}

const animationSource = declaration('function animatePlayerMarker(');
if (!animationSource) {
  expect('smooth player movement', false, 'function animatePlayerMarker is missing');
} else {
  const frames = [];
  const requestAnimationFrame = callback => {
    frames.push(callback);
    return frames.length;
  };
  const cancelAnimationFrame = () => {};
  const performance = {now: () => 0};
  const animate = new Function(
    'requestAnimationFrame', 'cancelAnimationFrame', 'performance',
    `${animationSource}; return animatePlayerMarker;`
  )(requestAnimationFrame, cancelAnimationFrame, performance);
  const marker = {
    point: {lat: 0, lng: 0},
    getLatLng() { return this.point; },
    setLatLng([lat, lng]) { this.point = {lat, lng}; }
  };
  animate(marker, [-10, 20], 1000);
  expect(
    'movement does not teleport',
    marker.point.lat === 0 && marker.point.lng === 0 && frames.length === 1,
    `marker immediately moved to ${marker.point.lat},${marker.point.lng}`
  );
  frames.shift()(500);
  expect(
    'movement has an intermediate position',
    marker.point.lat < 0 && marker.point.lat > -10
      && marker.point.lng > 0 && marker.point.lng < 20,
    `halfway position was ${marker.point.lat},${marker.point.lng}`
  );
  frames.shift()(1000);
  expect(
    'movement reaches the target',
    marker.point.lat === -10 && marker.point.lng === 20,
    `final position was ${marker.point.lat},${marker.point.lng}`
  );
}

if (failed) process.exitCode = 1;
