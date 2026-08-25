# How to add a new settings screen

This guide covers how to add a settings page that is visually identical
to every other settings page in the app, with zero design decisions.

## Quick template

```xml
<ScrollView style="@style/App.PageScroll">
    <LinearLayout style="@style/App.PageContent">

        <com.google.android.material.card.MaterialCardView
            style="@style/App.Card.Md"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginStart="@dimen/app_card_gap"
            android:layout_marginEnd="@dimen/app_card_gap"
            android:layout_marginTop="@dimen/app_card_gap"
            android:layout_marginBottom="@dimen/app_card_gap">
            <LinearLayout style="@style/App.Row.Nav">
                <LinearLayout style="@style/App.Row.TextBlock">
                    <TextView style="@style/App.Row.Title"
                        android:text="setting name" />
                    <TextView style="@style/App.Row.Subtitle"
                        android:text="what it does" />
                </LinearLayout>
                <TextView style="@style/App.Text.Chevron"
                    android:text="›" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

    </LinearLayout>
</ScrollView>
```

## Row types

| Style | Trailing element |
|---|---|
| App.Row.Nav | chevron › |
| App.Row.Toggle | SwitchMaterial |
| App.Row.Value | App.Row.ValueText |
| App.Row.Action | MaterialButton |
| App.Row.Reserved | nothing (greyed) |

## Activity contract

```kotlin
class NewActivity : AppCompatActivity(), SettingsPage {
    override val pageId    = "settings.platform.newsetting"
    override val pageTitle = "new setting"
    override fun getTrackedKeys() = listOf("pref_key_here")
}
```

## Rules

- All visible text lowercase, abbreviations uppercase (see UI_TEXT_CONVENTIONS.md)
- Never hardcode dp/sp/color values — use tokens
- Never set padding on scroll containers — use App.PageContent
- Card margins: always app_card_gap on all four sides
