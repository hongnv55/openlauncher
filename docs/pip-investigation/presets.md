Hình dung toàn bộ preset set:

```text
1. FOCUS
Landscape

┌─────────────────────────────┐
│                             │
│              A              │
│                             │
└─────────────────────────────┘


2. LANDSCAPE_PRIMARY_COMPANION

┌─────────────────────┬───────┐
│                     │       │
│          A          │   B   │
│                     │       │
└─────────────────────┴───────┘


4. ULTRAWIDE_TRIPLE

┌──────────────────┬─────────┬─────────┐
│                  │         │         │
│        A         │    B    │    C    │
│                  │         │         │
└──────────────────┴─────────┴─────────┘


5. PORTRAIT_STACK

┌───────────────┐
│               │
│       A       │
│               │
├───────────────┤
│       B       │
└───────────────┘


6. PORTRAIT_HERO_GRID

┌───────────────┐
│               │
│       A       │
│               │
├───────┬───────┤
│   B   │   C   │
└───────┴───────┘
```

Mình sẽ map chúng vào screen class như sau:

```text
LANDSCAPE
    ├── Focus
    ├── Primary + Companion

ULTRAWIDE
    └── Triple Wide

PORTRAIT
    ├── Portrait Stack
    └── Portrait Hero + Grid
```

Và về tỷ lệ diện tích mặc định, có thể lấy baseline:

```text
Primary + Companion
A : B ≈ 70 : 30

Triple Wide (ratio > 2.1)
A : B : C ≈ 50 : 25 : 25

Portrait Stack
A : B ≈ 65 : 35

Portrait Hero + Grid
A : bottom-row ≈ 65 : 35
B : C ≈ 50 : 50
```

Bạn không cần tạo logic layout riêng cho từng app. Launcher chỉ cần quản lý:

```java
enum LayoutPreset {
    FOCUS,

    LANDSCAPE_PRIMARY_COMPANION,

    ULTRAWIDE_TRIPLE,

    PORTRAIT_STACK,
    PORTRAIT_HERO_GRID
}
```
