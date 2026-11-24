export function installEntityControls({
  manifest,
  message,
  focusLocation,
  onChange
}) {
  const settings = manifest.presentation ?? {};
  const playerSearch = document.querySelector('#player-search');
  const waypointSearch = document.querySelector('#waypoint-search');
  const playerToggle = document.querySelector('#show-players');
  const waypointToggle = document.querySelector('#show-waypoints');
  const waypointList = document.querySelector('#waypoint-list');
  const waypointCount = document.querySelector('#waypoint-count');
  const namespace = `conflux-map-entities:${settings.serverInstanceId ?? 'legacy'}:${manifest.worldId}`;
  const read = (name) => {
    try {
      return localStorage.getItem(`${namespace}:${name}`) !== 'false';
    } catch {
      return true;
    }
  };
  playerToggle.checked = read('players');
  waypointToggle.checked = read('waypoints');
  function save(name, value) {
    try {
      localStorage.setItem(`${namespace}:${name}`, String(value));
    } catch {}
  }
  playerSearch.hidden = settings.allowPlayerSearch !== true;
  waypointSearch.hidden = settings.allowWaypointSearch === false;
  for (const [input, label] of [
    [playerSearch, 'searchPlayers'],
    [waypointSearch, 'searchWaypoints']
  ]) {
    input.placeholder = message(label);
    input.setAttribute('aria-label', message(label));
    input.addEventListener('input', onChange);
  }
  for (const [toggle, name] of [
    [playerToggle, 'players'],
    [waypointToggle, 'waypoints']
  ]) {
    toggle.addEventListener('change', () => {
      save(name, toggle.checked);
      onChange();
    });
  }
  function dimensionName(index) {
    return (
      manifest.dimensions.find((item) => item.index === index)?.id ??
      String(index)
    );
  }
  function matches(item, input) {
    if (input.hidden) return true;
    const query = input.value.trim().normalize('NFKC').toLocaleLowerCase();
    const haystack =
      `${item.name} ${dimensionName(item.dimension)} ${Math.floor(item.x)} ${Math.floor(item.z)}`
        .normalize('NFKC')
        .toLocaleLowerCase();
    return query.split(/\s+/).every((part) => haystack.includes(part));
  }
  function render(waypoints) {
    const rows = [...waypoints]
      .filter((item) => matches(item, waypointSearch))
      .sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
    waypointCount.textContent = String(rows.length);
    waypointList.replaceChildren();
    if (!rows.length) {
      const empty = document.createElement('p');
      empty.className = 'empty-state';
      empty.textContent = message(
        waypoints.length ? 'noMatches' : 'noWaypoints'
      );
      waypointList.append(empty);
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const item of rows) {
      const button = document.createElement('button');
      button.className = 'player-row';
      button.type = 'button';
      const icon = document.createElement('span');
      icon.className = 'waypoint-list-icon';
      icon.style.backgroundColor = `#${((item.colorArgb >>> 0) & 0xffffff).toString(16).padStart(6, '0')}`;
      icon.textContent = item.type === 'DEATH' ? '✕' : '◆';
      const detail = document.createElement('span');
      detail.className = 'player-details';
      const name = document.createElement('strong');
      name.textContent = item.name;
      const coords = document.createElement('small');
      coords.textContent = `${dimensionName(item.dimension)} · ${Math.floor(item.x)}, ${Math.floor(item.y)}, ${Math.floor(item.z)}`;
      detail.append(name, coords);
      button.append(icon, detail);
      button.addEventListener('click', () => {
        if (!waypointToggle.checked) {
          waypointToggle.checked = true;
          save('waypoints', true);
          onChange();
        }
        focusLocation(item);
      });
      fragment.append(button);
    }
    waypointList.append(fragment);
  }
  return {
    policy: (p) => {
      if ('allowPlayerSearch' in p) playerSearch.hidden = !p.allowPlayerSearch;
      if ('allowWaypointSearch' in p)
        waypointSearch.hidden = !p.allowWaypointSearch;
      if ('sharePlayers' in p) {
        playerToggle.disabled = !p.sharePlayers;
        if (!p.sharePlayers) playerSearch.value = '';
      }
    },
    showPlayers: () => playerToggle.checked && !playerToggle.disabled,
    showWaypoints: () => waypointToggle.checked,
    filterPlayers: (players) =>
      players.filter((item) => matches(item, playerSearch)),
    render
  };
}
