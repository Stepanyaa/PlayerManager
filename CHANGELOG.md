# PlayerManager — Changelog

## v2.0.0

Compared with the last public release **v1.1.2**
(https://github.com/Stepanyaa/PlayerManager, 4 Java classes, 7 `messages_xx.yml`
files, a 43-line `config.yml`).

### Architecture

- The plugin grew from **4 classes to 34**, split into packages:
  `inventory`, `jail`, `punish`, `quit`, `storage`, `util`.
- Every heavy operation (skin lookups, item serialization, SQLite, YAML saves)
  now runs **off the main thread**; only Bukkit inventory mutations stay
  synchronous, as the API requires.
- New **SQLite storage** (`playermanager.db`, WAL mode) via `storage/DataStore`:
  skins and inventory / ender-chest blobs are stored there instead of bloating
  `player_data.yml`. Writes are batched and flushed on a timer
  (`storage.flush-interval-seconds`).
- `player_data.yml` saves are debounced instead of being written on every change.

### Localization

- Language files renamed from `messages_en.yml` style to **Minecraft locale
  codes**: `en_us`, `ru_ru`, `de_de`, `fr_fr`, `pl_pl`, `pt_br`, `tr_tr`.
  Legacy files are migrated automatically.
- **Per-player language**: messages follow each admin's client locale
  (`util/LocaleListener`), instead of one global `language:` value.
- All 7 files now share an **identical key set (423 keys)**; every key used by
  the code exists in every file, placeholders match, and old key layouts
  (`filter.recently-left.*`, `action.action.*`, `gui.ban.duration.*`,
  `error.ban/jail/warn/mute/kick/unban`, `gui.reason`) are migrated on start-up.
- `MessageManager` never returns a YAML section or list any more, so no message
  can print `MemorySection[path=...]`.

### Player list and heads

- **Real skins on heads** (`util/SkinService`): local skin cache plus
  SkinsRestorer support, textures resolved asynchronously, offline players keep
  their skin.
- **Activity badges** (`activity-status` in config): status by time since the
  last login (online / just now / today / yesterday / week / month / 3, 6, 12
  months), with configurable icon and colour and fully localized text.
- **"Recently left" filter** (clock icon) with quit categories (under a minute,
  5 / 10 / 30 minutes, an hour, today, yesterday).
- `quit/QuitTracker` + `quit/QuitInfo` remember quit time, localized quit
  reason, world and coordinates — including "teleport to the last known
  location" for offline players.
- Admins listed in `admin-uuids` are filtered out of the list without leaving
  empty slots, pagination is calculated from the filtered list.
- Name resolution (`util/NameUtil`) handles special characters, raw UUIDs and
  offline players.

### Inventory viewer

- Rewritten as a **two-panel viewer**: main inventory, armour and off-hand plus
  a control row (back, player menu, ender chest, teleport, info, UUID copy,
  stats, effects, refresh).
- **Ender chest viewing** in the same window layout.
- **Editing inventories of online *and* offline players**
  (`playermanager.inventory.edit`); offline edits are stored and applied the
  moment the player joins.
- **Live synchronization**: while a viewer window is open, changes on both sides
  are merged slot by slot (`inventory-viewer.live-sync-ticks`), so "Refresh"
  can no longer delete items the player picked up.
- **Duplication protection**: pending admin edits are applied inside the join
  event and again as a backstop when the chest is opened.
- The admin's own inventory is backed up and restored around viewing sessions.

### Punishments

- New `punish/PunishmentManager`: **real temporary bans** without any external
  plugin (the old build could only run vanilla `/ban`).
- GUI flows for duration and reason selection for **ban / tempban / mute /
  warn**, plus kick reasons and quick reason presets.
- Mute enforcement and a warning system with configurable durations.
- New `tempban` sub-command and the `playermanager.punishment` permission.

### Jail system (new)

- `jail/JailManager` with **13 providers**: Essentials, CMI, LiteBans,
  AdvancedBan, Jails, DeluxeJails, Prison, JailSystem, a custom provider and a
  generic command-based provider.
- Jail GUI with 13 durations (1m … 7d, permanent), quick reasons and a
  **jail history GUI** backed by persistent history storage.
- New `jail` / `unjail` sub-commands and the `playermanager.jail` permission.

### Other features

- **Freeze** support (`util/FreezeManager`, `freeze` section in config).
- Player info screen with level, hunger, gamemode, stats, active effects and a
  click-to-copy UUID; the same information block is shown on the head, in the
  player menu and on the info screen.
- Gamemode GUI with a confirmation step for creative.
- IP search GUI improvements, including mass ban / unban by IP.
- Config grew to 19 sections: `activity-status`, `inventory-viewer`,
  `offline-inventory`, `quit-tracking`, `jail`, `punishments`, `time-labels`,
  `storage`, `freeze`, `admin-uuids`, `date-format`, `features`, and more.
- New permissions: `playermanager.inventory.edit`, `playermanager.jail`,
  `playermanager.punishment`.

### Fixes in this build

1. Clock ("recently left") filter showed `MemorySection[path='filter.recently-left']`.
2. Activity line showed the raw colour name and untranslated text
   (`Активность: AQUA Was her yestaday`).
3. Quit reason was English only — now translated via `quit-reason.*`.
4. Duplicate information: the activity line is hidden when the quit category is
   already shown (`activity-status.hide-when-quit-known`).
5. The player menu's information item now shows everything from the main menu.
6. No more empty slots caused by hidden admins.
7. Inventory title showed `Инвентарь: %player%Имя игрока`.
8. "Refresh" no longer loses items added by the player (inventory and ender chest).
9. Real-time inventory / ender-chest updates without server lag.
10. All heavy methods made asynchronous.
11. Ender-chest duplication window after a player rejoined is closed.
12. All language files share the same keys.
13. Teleport message showed `%player%<name>` — placeholders are now always
    substituted instead of being concatenated.

---

# PlayerManager — История изменений

## v2.0.0

Сравнение с последним публичным релизом **v1.1.2**
(https://github.com/Stepanyaa/PlayerManager: 4 Java-класса, 7 файлов
`messages_xx.yml`, `config.yml` на 43 строки).

### Архитектура

- Плагин вырос с **4 классов до 34**, код разложен по пакетам `inventory`,
  `jail`, `punish`, `quit`, `storage`, `util`.
- Все тяжёлые операции (скины, сериализация предметов, SQLite, запись YAML)
  выполняются **вне главного потока**; синхронными остались только изменения
  инвентарей — так требует Bukkit.
- Новое **хранилище SQLite** (`playermanager.db`, режим WAL) в
  `storage/DataStore`: скины и содержимое инвентарей/эндер-сундуков больше не
  раздувают `player_data.yml`, запись пакетная по таймеру.
- Сохранение `player_data.yml` дебаунсится вместо записи на каждое изменение.

### Локализация

- Языковые файлы переименованы в **коды локалей Minecraft**: `en_us`, `ru_ru`,
  `de_de`, `fr_fr`, `pl_pl`, `pt_br`, `tr_tr`. Старые файлы миграруются
  автоматически.
- **Язык на каждого игрока**: сообщения идут по локали клиента администратора
  (`util/LocaleListener`), а не по одному глобальному `language:`.
- Во всех 7 файлах **одинаковый набор из 423 ключей**; все ключи из кода есть в
  каждом файле, плейсхолдеры совпадают, старые схемы ключей переносятся при
  запуске.
- `MessageManager` больше никогда не возвращает секцию или список, поэтому
  `MemorySection[path=...]` в интерфейсе появиться не может.

### Список игроков и головы

- **Настоящие скины на головах** (`util/SkinService`): локальный кэш скинов и
  поддержка SkinsRestorer, текстуры подгружаются асинхронно, у оффлайн-игроков
  скин сохраняется.
- **Бейджи активности** (`activity-status`): статус по времени с последнего
  входа (сейчас в сети / только что / сегодня / вчера / неделя / месяц / 3, 6,
  12 месяцев), с настраиваемой иконкой, цветом и переводимым текстом.
- **Фильтр «Недавно вышел»** (часы) с категориями: менее минуты, 5, 10, 30
  минут, часа, сегодня, вчера.
- `quit/QuitTracker` и `quit/QuitInfo` помнят время выхода, локализованную
  причину, мир и координаты — включая телепорт к последней известной позиции.
- Админы из `admin-uuids` убираются из списка без пустых слотов, страницы
  считаются по отфильтрованному списку.
- Поиск имён (`util/NameUtil`) понимает особые символы, UUID и оффлайн-игроков.

### Просмотр инвентаря

- Полностью переписан: **две панели** — инвентарь, броня и левая рука плюс ряд
  управления (назад, меню игрока, эндер-сундук, телепорт, информация, копирование
  UUID, статистика, эффекты, обновить).
- **Просмотр эндер-сундука** в том же формате окна.
- **Редактирование инвентаря онлайн и оффлайн игроков**
  (`playermanager.inventory.edit`); правки оффлайн-игроков применяются в момент
  входа.
- **Живая синхронизация**: пока окно открыто, изменения с двух сторон
  объединяются по слотам (`inventory-viewer.live-sync-ticks`), поэтому
  «Обновить» больше не удаляет поднятые игроком предметы.
- **Защита от дюпа**: отложенные правки применяются внутри события входа и
  повторно страхуются при открытии сундука.
- Инвентарь администратора сохраняется и восстанавливается вокруг сессии просмотра.

### Наказания

- Новый `punish/PunishmentManager`: **настоящие временные баны** без сторонних
  плагинов (раньше вызывался только ванильный `/ban`).
- GUI выбора длительности и причины для **бана / темпбана / мута /
  предупреждения**, причины кика и быстрые пресеты.
- Реальное действие мута и система предупреждений с настраиваемыми сроками.
- Новая подкоманда `tempban` и право `playermanager.punishment`.

### Система тюрем (новое)

- `jail/JailManager` с **13 провайдерами**: Essentials, CMI, LiteBans,
  AdvancedBan, Jails, DeluxeJails, Prison, JailSystem, собственный провайдер и
  универсальный командный.
- GUI тюрьмы с 13 сроками (1м … 7д, навсегда), быстрые причины и **GUI истории
  тюрем** с постоянным хранением истории.
- Новые подкоманды `jail` / `unjail` и право `playermanager.jail`.

### Прочее

- Поддержка **заморозки** (`util/FreezeManager`, секция `freeze`).
- Экран информации: уровень, сытость, режим игры, статистика, активные эффекты,
  UUID с копированием по клику; один и тот же блок информации показывается на
  голове, в меню игрока и на экране информации.
- GUI смены режима игры с подтверждением для креатива.
- Улучшения GUI поиска по IP, включая массовый бан/разбан по IP.
- Конфиг вырос до 19 секций: `activity-status`, `inventory-viewer`,
  `offline-inventory`, `quit-tracking`, `jail`, `punishments`, `time-labels`,
  `storage`, `freeze`, `admin-uuids`, `date-format`, `features` и другие.
- Новые права: `playermanager.inventory.edit`, `playermanager.jail`,
  `playermanager.punishment`.

### Исправления в этой сборке

1. Фильтр-часы показывал `MemorySection[path='filter.recently-left']`.
2. Строка активности показывала название цвета и непереведённый текст
   (`Активность: AQUA Was her yestaday`).
3. Причина выхода была только на английском — теперь через `quit-reason.*`.
4. Дубль информации: строка активности скрывается, когда уже показана категория
   выхода (`activity-status.hide-when-quit-known`).
5. Пункт «Информация» в меню игрока показывает всё, что есть в главном меню.
6. Пустые слоты из-за скрытых админов убраны.
7. Заголовок инвентаря показывал `Инвентарь: %player%Имя игрока`.
8. «Обновить» больше не теряет добавленные предметы (инвентарь и эндер-сундук).
9. Обновление инвентаря и сундука в реальном времени без лагов.
10. Все тяжёлые методы стали асинхронными.
11. Закрыто окно дюпа через эндер-сундук после повторного входа игрока.
12. Все языковые файлы имеют одинаковые ключи.
13. Сообщение о телепортации показывало `%player%ник` — плейсхолдеры теперь
    всегда подставляются, а не склеиваются с ником.
