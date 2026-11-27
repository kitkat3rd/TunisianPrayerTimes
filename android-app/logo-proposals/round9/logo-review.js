const gallery = document.querySelector('#gallery');
const slideshow = document.querySelector('#slideshow');
const toolbar = document.querySelector('#review-toolbar');
const query = document.querySelector('#query');
const category = document.querySelector('#group');
const resultCount = document.querySelector('#count');
const empty = document.querySelector('#empty');
const viewButtons = [...toolbar.querySelectorAll('[data-view]')];
const ratingFilters = [...toolbar.querySelectorAll('[data-filter-rating]')];
const ratingTemplate = document.querySelector('#rating-buttons');
const storageNotice = document.querySelector('#rating-storage-note');
const storagePrefix = 'tunisian-prayer-times.logo-review.v1:';
const ratings = new Map();

const slide = {
  art: document.querySelector('#slide-art'),
  copy: slideshow.querySelector('.slide-copy'),
  title: document.querySelector('#slide-title'),
  category: document.querySelector('#slide-category'),
  description: document.querySelector('#slide-description'),
  files: document.querySelector('#slide-files'),
  ratings: ratingTemplate.content.firstElementChild.cloneNode(true),
  previous: document.querySelector('#slide-previous'),
  next: document.querySelector('#slide-next'),
  position: document.querySelector('#slide-position'),
  count: document.querySelector('#slide-count'),
};

const groups = [...gallery.querySelectorAll('.archive-group')].map(section => ({
  section,
  name: section.dataset.group,
  count: section.querySelector('h2 small'),
  records: [],
}));
const records = [];
let visibleRecords = [];
let ratingFilter = '';
let view = 'gallery';
let slideIndex = 0;
let activeRecord;
let displayedRecord;
let lastPreview;

for (const group of groups) {
  category.add(new Option(group.name));
  for (const details of group.section.querySelectorAll('.record')) {
    const title = details.querySelector('.record-title').textContent;
    const files = details.querySelector('.src-list');
    const id = JSON.stringify([group.name, files.querySelector('a').getAttribute('href'), title]);
    const card = document.createElement('article');
    card.className = 'review-card';
    const preview = document.createElement('button');
    preview.type = 'button';
    preview.className = 'review-preview';
    preview.setAttribute('aria-label', 'View ' + title + ' in slideshow');
    preview.append(details.querySelector('.archive-art'));
    const ratingGroup = ratingTemplate.content.firstElementChild.cloneNode(true);
    ratingGroup.setAttribute('aria-label', 'Rate ' + title);
    details.before(card);
    card.append(preview, details, ratingGroup);
    const record = {
      card, details, files, group, id, preview, ratingGroup, title,
      description: details.querySelector('p:not(.source-note)')?.textContent ?? '',
    };
    preview.addEventListener('click', () => {
      lastPreview = preview;
      setView('slideshow', record);
      slideshow.scrollIntoView({ block: 'nearest' });
      slide.title.focus({ preventScroll: true });
    });
    ratingGroup.addEventListener('click', event => {
      const button = event.target.closest('button[data-rating]');
      if (button) rate(record, button.dataset.rating);
    });
    group.records.push(record);
    records.push(record);
  }
}

function rememberRating(id, rating) {
  if (rating === 'liked' || rating === 'disliked') {
    ratings.set(id, rating);
  } else {
    ratings.delete(id);
  }
}

function paintRating(ratingGroup, id) {
  for (const button of ratingGroup.querySelectorAll('[data-rating]')) {
    button.setAttribute('aria-pressed', String(ratings.get(id) === button.dataset.rating));
  }
}

function rate(record, rating) {
  const next = ratings.get(record.id) === rating ? null : rating;
  rememberRating(record.id, next);
  try {
    const key = storagePrefix + record.id;
    if (next) {
      localStorage.setItem(key, next);
    } else {
      localStorage.removeItem(key);
    }
    storageNotice.textContent = 'Ratings are saved in this browser.';
  } catch {
    storageNotice.textContent = 'Browser storage is unavailable. Ratings last until you close or reload this page.';
  }
  paintRating(record.ratingGroup, record.id);
  if (view === 'slideshow' && activeRecord === record) paintRating(slide.ratings, record.id);
  if (!ratingFilter) {
    updateRatingCounts();
    return;
  }
  updateFilters(true);
  if (view === 'gallery' && record.card.hidden) {
    toolbar.querySelector('[data-filter-rating][aria-pressed="true"]').focus();
  } else if (view === 'slideshow' && !activeRecord) {
    empty.focus();
  }
}

function updateRatingCounts() {
  const counts = { '': 0, liked: 0, disliked: 0 };
  for (const record of records) {
    if (!record.matchesFilters) continue;
    counts['']++;
    const rating = ratings.get(record.id);
    if (rating) counts[rating]++;
  }
  for (const button of ratingFilters) {
    button.querySelector('span').textContent = counts[button.dataset.filterRating];
    button.setAttribute('aria-pressed', String(button.dataset.filterRating === ratingFilter));
  }
}

function updateFilters(keepPosition = false) {
  const terms = query.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
  visibleRecords = [];
  for (const group of groups) {
    let count = 0;
    const matchesCategory = !category.value || category.value === group.name;
    for (const record of group.records) {
      record.matchesFilters = matchesCategory && terms.every(term => record.details.dataset.query.includes(term));
      const matches = record.matchesFilters && (!ratingFilter || ratings.get(record.id) === ratingFilter);
      record.card.hidden = !matches;
      if (matches) {
        count++;
        visibleRecords.push(record);
      }
    }
    group.section.hidden = count === 0;
    group.count.textContent = count;
  }
  updateRatingCounts();
  const count = visibleRecords.length;
  resultCount.textContent = count + (count === 1 ? ' design' : ' designs');
  empty.textContent = ratingFilter
    ? 'No ' + ratingFilter + ' designs match these filters.'
    : 'No designs match these filters.';
  const retainedIndex = visibleRecords.indexOf(activeRecord);
  if (retainedIndex >= 0) {
    slideIndex = retainedIndex;
  } else {
    slideIndex = keepPosition ? Math.min(slideIndex, Math.max(count - 1, 0)) : 0;
  }
  renderView();
}

function setView(nextView, record) {
  view = nextView;
  if (record) slideIndex = visibleRecords.indexOf(record);
  renderView();
}

function renderView() {
  const hasResults = visibleRecords.length > 0;
  gallery.hidden = view !== 'gallery' || !hasResults;
  slideshow.hidden = view !== 'slideshow' || !hasResults;
  empty.hidden = hasResults;
  for (const button of viewButtons) {
    button.setAttribute('aria-pressed', String(button.dataset.view === view));
  }
  activeRecord = visibleRecords[slideIndex];
  if (view === 'slideshow' && hasResults) renderSlide(activeRecord);
}

function renderSlide(record) {
  if (displayedRecord !== record) {
    const art = record.preview.querySelector('.archive-thumb').cloneNode(true);
    for (const image of art.querySelectorAll('img')) image.removeAttribute('loading');
    slide.art.replaceChildren(art);
    slide.title.textContent = record.title;
    slide.category.textContent = record.group.name;
    slide.description.textContent = record.description;
    slide.description.hidden = !record.description;
    slide.files.lastElementChild.replaceWith(record.files.cloneNode(true));
    slide.files.open = false;
    slide.copy.scrollTop = 0;
    slide.ratings.setAttribute('aria-label', 'Rate ' + record.title);
    displayedRecord = record;
  }
  paintRating(slide.ratings, record.id);
  slide.previous.disabled = slideIndex === 0;
  slide.next.disabled = slideIndex === visibleRecords.length - 1;
  slide.position.max = visibleRecords.length;
  slide.position.value = slideIndex + 1;
  slide.position.disabled = visibleRecords.length === 1;
  slide.count.textContent = (slideIndex + 1) + ' of ' + visibleRecords.length;
  slide.position.setAttribute('aria-valuetext', slide.count.textContent + ' · ' + record.title);
}

function goToSlide(index) {
  if (index < 0 || index >= visibleRecords.length) return;
  slideIndex = index;
  renderView();
}

function applyLink() {
  const params = new URLSearchParams(location.hash.slice(1));
  const linkedCategory = params.get('category');
  category.value = groups.some(group => group.name === linkedCategory) ? linkedCategory : '';
  view = params.get('view') === 'slideshow' ? 'slideshow' : 'gallery';
  updateFilters();
}

for (const button of viewButtons) {
  button.addEventListener('click', () => {
    setView(button.dataset.view);
    if (view === 'gallery' && lastPreview?.offsetWidth) lastPreview.focus();
    if (view === 'gallery') lastPreview = undefined;
  });
}
for (const button of ratingFilters) {
  button.addEventListener('click', () => {
    ratingFilter = button.dataset.filterRating;
    updateFilters();
  });
}
query.addEventListener('input', () => updateFilters());
category.addEventListener('change', () => updateFilters());
document.querySelector('#reset').addEventListener('click', () => {
  query.value = '';
  category.value = '';
  ratingFilter = '';
  updateFilters();
  query.focus();
});

document.querySelector('#slide-ratings').append(slide.ratings);
slide.ratings.addEventListener('click', event => {
  const button = event.target.closest('button[data-rating]');
  if (!button || !activeRecord) return;
  const nextRecord = visibleRecords[slideIndex + 1];
  rate(activeRecord, button.dataset.rating);
  if (nextRecord && nextRecord !== activeRecord) {
    goToSlide(visibleRecords.indexOf(nextRecord));
  }
});
slide.previous.addEventListener('click', () => goToSlide(slideIndex - 1));
slide.next.addEventListener('click', () => goToSlide(slideIndex + 1));
slide.position.addEventListener('input', () => goToSlide(slide.position.valueAsNumber - 1));
document.addEventListener('keydown', event => {
  if (slideshow.hidden || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey ||
      event.target.closest('input, select, textarea, [contenteditable="true"]')) return;
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault();
    goToSlide(slideIndex + (event.key === 'ArrowLeft' ? -1 : 1));
  }
});
window.addEventListener('hashchange', applyLink);
window.addEventListener('storage', event => {
  if (event.key === null) {
    ratings.clear();
    for (const record of records) paintRating(record.ratingGroup, record.id);
  } else if (event.key.startsWith(storagePrefix)) {
    const record = records.find(item => storagePrefix + item.id === event.key);
    if (!record) return;
    rememberRating(record.id, event.newValue);
    paintRating(record.ratingGroup, record.id);
  } else {
    return;
  }
  updateFilters(true);
});

try {
  const storage = localStorage;
  for (const record of records) {
    rememberRating(record.id, storage.getItem(storagePrefix + record.id));
    paintRating(record.ratingGroup, record.id);
  }
} catch {
  storageNotice.textContent = 'Saved ratings could not be loaded. You can still rate logos during this review.';
}
applyLink();
toolbar.hidden = false;
