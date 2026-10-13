#include <pebble.h>

#define PADDING PBL_IF_ROUND_ELSE(16, 4)
#define RATING_HEIGHT 28
#define MAX_SAVED 24
#define MAX_OUT 32
#define WORD_LEN 32
#define DATE_LEN 20
#define MEANING_LEN 100
#define PERSIST_COUNT 0  // entries live at persist keys 1..count

// A rated word. word + date identify it, matching the Android app's "date word" key.
typedef struct {
  char word[WORD_LEN];
  char date[DATE_LEN];
  char meaning[MEANING_LEN];
  uint8_t rating;
} Saved;

static Window *s_window;
static ScrollLayer *s_scroll;
static TextLayer *s_rating_layer;
static TextLayer *s_word_layer;
static TextLayer *s_body_layer;
static Window *s_saved_window;
static MenuLayer *s_menu;

// Text must outlive the layers; the phone sends strings that fit the 1 KB inbox.
static char s_word[64] = "Word of the Day";
static char s_date[DATE_LEN];
static char s_meaning[MEANING_LEN];
static char s_body[900] = "Loading...\n\nSELECT rates, hold SELECT for saved words.";
static char s_rating_text[24];

static Saved s_saved[MAX_SAVED];  // newest first
static int s_saved_count;

// Rating events waiting to go to the phone, sent one at a time.
static Saved s_out[MAX_OUT];
static int s_out_head;
static int s_out_count;
static bool s_sending;

// ---- Saved words ----

static void prv_stars(char *out, int rating) {
  for (int i = 0; i < 5; i++) {
    out[i] = i < rating ? '*' : '-';
  }
  out[5] = '\0';
}

static int prv_find(const char *word, const char *date) {
  for (int i = 0; i < s_saved_count; i++) {
    if (!strcmp(s_saved[i].word, word) && !strcmp(s_saved[i].date, date)) {
      return i;
    }
  }
  return -1;
}

static void prv_persist(void) {
  persist_write_int(PERSIST_COUNT, s_saved_count);
  for (int i = 0; i < s_saved_count; i++) {
    persist_write_data(1 + i, &s_saved[i], sizeof(Saved));
  }
}

static void prv_load(void) {
  int count = persist_read_int(PERSIST_COUNT);
  for (int i = 0; i < count && i < MAX_SAVED; i++) {
    if (persist_read_data(1 + i, &s_saved[i], sizeof(Saved)) != (int)sizeof(Saved)) {
      break;
    }
    s_saved[i].word[WORD_LEN - 1] = s_saved[i].date[DATE_LEN - 1] = s_saved[i].meaning[MEANING_LEN - 1] = '\0';
    s_saved_count++;
  }
}

// Rating 0 removes the word; a new word goes to the front, dropping the oldest when full.
static void prv_set(const Saved *e) {
  const int i = prv_find(e->word, e->date);
  if (e->rating == 0) {
    if (i >= 0) {
      memmove(&s_saved[i], &s_saved[i + 1], (s_saved_count - i - 1) * sizeof(Saved));
      s_saved_count--;
    }
  } else if (i >= 0) {
    s_saved[i] = *e;
  } else {
    if (s_saved_count == MAX_SAVED) {
      s_saved_count--;
    }
    memmove(&s_saved[1], &s_saved[0], s_saved_count * sizeof(Saved));
    s_saved[0] = *e;
    s_saved_count++;
  }
  prv_persist();
}

static int prv_current_rating(void) {
  const int i = prv_find(s_word, s_date);
  return i >= 0 ? s_saved[i].rating : 0;
}

static void prv_update_rating_text(void) {
  char stars[6];
  prv_stars(stars, prv_current_rating());
  snprintf(s_rating_text, sizeof(s_rating_text), "Rating: %s", stars);
  text_layer_set_text(s_rating_layer, s_rating_text);
  if (s_menu) {
    menu_layer_reload_data(s_menu);
  }
}

// ---- Phone sync ----

static void prv_out_pump(void) {
  if (s_sending || s_out_count == 0) {
    return;
  }
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }
  const Saved *e = &s_out[s_out_head];
  dict_write_cstring(iter, MESSAGE_KEY_WORD, e->word);
  dict_write_cstring(iter, MESSAGE_KEY_DATE, e->date);
  dict_write_cstring(iter, MESSAGE_KEY_MEANING, e->meaning);
  dict_write_uint8(iter, MESSAGE_KEY_RATING, e->rating);
  if (app_message_outbox_send() == APP_MSG_OK) {
    s_sending = true;
  }
}

static void prv_out_enqueue(const Saved *e) {
  if (s_out_count < MAX_OUT) {
    s_out[(s_out_head + s_out_count) % MAX_OUT] = *e;
    s_out_count++;
  }
  prv_out_pump();
}

static void prv_outbox_sent(DictionaryIterator *iter, void *context) {
  s_out_head = (s_out_head + 1) % MAX_OUT;
  s_out_count--;
  s_sending = false;
  prv_out_pump();
}

// Phone unreachable: drop the queue. The phone asks for a full resync the next time the app opens.
static void prv_outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  s_out_count = 0;
  s_sending = false;
}

static void prv_rate(const Saved *e) {
  prv_set(e);
  prv_out_enqueue(e);
  prv_update_rating_text();
}

// ---- Word screen ----

static void prv_layout(void) {
  const int16_t width = layer_get_bounds(window_get_root_layer(s_window)).size.w - 2 * PADDING;

  // Content size is measured against the layer's current width, so give it the real width first.
  layer_set_frame(text_layer_get_layer(s_word_layer), GRect(PADDING, 0, width, 1000));
  layer_set_frame(text_layer_get_layer(s_body_layer), GRect(PADDING, 0, width, 1000));
  text_layer_set_text(s_word_layer, s_word);
  text_layer_set_text(s_body_layer, s_body);

  const GSize word_size = text_layer_get_content_size(s_word_layer);
  const GSize body_size = text_layer_get_content_size(s_body_layer);

  layer_set_frame(text_layer_get_layer(s_word_layer),
                  GRect(PADDING, RATING_HEIGHT, width, word_size.h + 4));
  layer_set_frame(text_layer_get_layer(s_body_layer),
                  GRect(PADDING, RATING_HEIGHT + word_size.h + 8, width, body_size.h + 4));
  scroll_layer_set_content_size(
      s_scroll, GSize(width + 2 * PADDING, RATING_HEIGHT + word_size.h + body_size.h + 24));
  scroll_layer_set_content_offset(s_scroll, GPointZero, false);
  prv_update_rating_text();
}

static void prv_show_word(DictionaryIterator *iter) {
  Tuple *word = dict_find(iter, MESSAGE_KEY_WORD);
  Tuple *date = dict_find(iter, MESSAGE_KEY_DATE);
  Tuple *pronunciation = dict_find(iter, MESSAGE_KEY_PRONUNCIATION);
  Tuple *meaning = dict_find(iter, MESSAGE_KEY_MEANING);
  Tuple *details = dict_find(iter, MESSAGE_KEY_DETAILS);
  if (!word || !date || !pronunciation || !meaning || !details) {
    return;
  }
  snprintf(s_word, sizeof(s_word), "%s", word->value->cstring);
  snprintf(s_date, sizeof(s_date), "%s", date->value->cstring);
  snprintf(s_meaning, sizeof(s_meaning), "%s", meaning->value->cstring);
  snprintf(s_body, sizeof(s_body), "%s\n\n%s\n\n%s", pronunciation->value->cstring,
           meaning->value->cstring, details->value->cstring);
  prv_layout();
}

static void prv_select_click(ClickRecognizerRef recognizer, void *context) {
  if (!s_date[0]) {
    return;  // no word loaded yet
  }
  Saved e = { .rating = (prv_current_rating() + 1) % 6 };
  snprintf(e.word, sizeof(e.word), "%s", s_word);
  snprintf(e.date, sizeof(e.date), "%s", s_date);
  snprintf(e.meaning, sizeof(e.meaning), "%s", s_meaning);
  prv_rate(&e);
}

static void prv_saved_window_push(void);

static void prv_select_long_click(ClickRecognizerRef recognizer, void *context) {
  prv_saved_window_push();
}

static void prv_click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click);
  window_long_click_subscribe(BUTTON_ID_SELECT, 500, prv_select_long_click, NULL);
}

static void prv_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  const GRect bounds = layer_get_bounds(root);

  s_scroll = scroll_layer_create(bounds);
  scroll_layer_set_click_config_onto_window(s_scroll, window);
  scroll_layer_set_callbacks(s_scroll, (ScrollLayerCallbacks) { .click_config_provider = prv_click_config });
  scroll_layer_set_shadow_hidden(s_scroll, true);

  s_rating_layer = text_layer_create(
      GRect(PADDING, PBL_IF_ROUND_ELSE(8, 0), bounds.size.w - 2 * PADDING, RATING_HEIGHT));
  text_layer_set_font(s_rating_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  s_word_layer = text_layer_create(GRectZero);
  text_layer_set_font(s_word_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  s_body_layer = text_layer_create(GRectZero);
  text_layer_set_font(s_body_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24));

  scroll_layer_add_child(s_scroll, text_layer_get_layer(s_rating_layer));
  scroll_layer_add_child(s_scroll, text_layer_get_layer(s_word_layer));
  scroll_layer_add_child(s_scroll, text_layer_get_layer(s_body_layer));
  layer_add_child(root, scroll_layer_get_layer(s_scroll));
  prv_layout();
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_rating_layer);
  text_layer_destroy(s_word_layer);
  text_layer_destroy(s_body_layer);
  scroll_layer_destroy(s_scroll);
}

// ---- Saved words screen: SELECT cycles the highlighted word's rating (back to 0 removes it) ----

static uint16_t prv_menu_rows(MenuLayer *menu, uint16_t section, void *context) {
  return s_saved_count ? s_saved_count : 1;
}

static void prv_menu_draw_row(GContext *ctx, const Layer *cell, MenuIndex *index, void *context) {
  if (!s_saved_count) {
    menu_cell_basic_draw(ctx, cell, "No saved words", "SELECT on a word rates it", NULL);
    return;
  }
  const Saved *e = &s_saved[index->row];
  char stars[6];
  char subtitle[MEANING_LEN + 8];
  prv_stars(stars, e->rating);
  snprintf(subtitle, sizeof(subtitle), "%s %s", stars, e->meaning);
  menu_cell_basic_draw(ctx, cell, e->word, subtitle, NULL);
}

static void prv_menu_select(MenuLayer *menu, MenuIndex *index, void *context) {
  if (!s_saved_count) {
    return;
  }
  Saved e = s_saved[index->row];
  e.rating = (e.rating + 1) % 6;
  prv_rate(&e);
  const int last = s_saved_count ? s_saved_count - 1 : 0;
  menu_layer_set_selected_index(menu, (MenuIndex) { 0, index->row < last ? index->row : last },
                                MenuRowAlignCenter, false);
}

static void prv_saved_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = prv_menu_rows,
    .draw_row = prv_menu_draw_row,
    .select_click = prv_menu_select,
  });
  menu_layer_set_click_config_onto_window(s_menu, window);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void prv_saved_window_unload(Window *window) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
  window_destroy(s_saved_window);
  s_saved_window = NULL;
}

static void prv_saved_window_push(void) {
  s_saved_window = window_create();
  window_set_window_handlers(s_saved_window, (WindowHandlers) {
    .load = prv_saved_window_load,
    .unload = prv_saved_window_unload,
  });
  window_stack_push(s_saved_window, true);
}

// ---- Incoming messages ----

static void prv_inbox_received(DictionaryIterator *iter, void *context) {
  Tuple *rating = dict_find(iter, MESSAGE_KEY_RATING);
  Tuple *word = dict_find(iter, MESSAGE_KEY_WORD);
  Tuple *date = dict_find(iter, MESSAGE_KEY_DATE);
  Tuple *meaning = dict_find(iter, MESSAGE_KEY_MEANING);

  if (dict_find(iter, MESSAGE_KEY_SYNC)) {
    // The phone wants everything we have. Snapshot now so its own updates don't change what we send.
    for (int i = 0; i < s_saved_count; i++) {
      prv_out_enqueue(&s_saved[i]);
    }
  } else if (rating && word && date && meaning) {
    // A rating from the phone app: apply it without echoing it back.
    Saved e = { .rating = rating->value->uint8 };
    snprintf(e.word, sizeof(e.word), "%s", word->value->cstring);
    snprintf(e.date, sizeof(e.date), "%s", date->value->cstring);
    snprintf(e.meaning, sizeof(e.meaning), "%s", meaning->value->cstring);
    prv_set(&e);
    prv_update_rating_text();
  } else {
    prv_show_word(iter);
  }
}

int main(void) {
  prv_load();
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_outbox_sent(prv_outbox_sent);
  app_message_register_outbox_failed(prv_outbox_failed);
  app_message_open(1024, 256);
  window_stack_push(s_window, true);
  app_event_loop();
  window_destroy(s_window);
}
