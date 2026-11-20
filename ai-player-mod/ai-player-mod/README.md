# AI Player MOD (Fabric 1.20.1)

LLM（Claude / ChatGPT / Gemini / Kimi / Grok）が **本物のプレイヤーとして自律的にマイクラをプレイ**するMODです。
チャットで話しかけると、その指示を優先します。指示がなければ自分で判断して進めます（木→作業台→ツルハシ→石→鉄…）。

## ビルド方法
1. JDK 17 を用意
2. このフォルダで `gradle wrapper`（または Fabric 公式テンプレートの `gradlew` をコピー）→ `./gradlew build`
3. `build/libs/ai-player-1.0.0.jar` を `mods/` に入れる（Fabric Loader 0.15+ と Fabric API 0.92.2+1.20.1 が必要）
   - シングルプレイでも専用サーバーでも動きます（サーバー側で動くMODです）

## 設定
初回起動で `config/aiplayer.json` が生成されます。使うAIの `apiKey` を入れてください。
（環境変数でも可: `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `GEMINI_API_KEY` / `MOONSHOT_API_KEY` / `XAI_API_KEY`）

| provider | 会社 | 既定モデル(設定で変更可) |
|---|---|---|
| `claude` | Anthropic | claude-sonnet-5-5 |
| `openai` | ChatGPT | gpt-5 |
| `gemini` | Google | gemini-2.5-pro |
| `kimi` | Moonshot | kimi-k2-0905-preview |
| `grok` | xAI | grok-4 |

モデル名は古くなるので、`model` を最新のものに書き換えてください。

## 使い方
- `/aiplayer spawn` … 自分の位置にAIを出現（OP権限が必要）
- `/aiplayer provider gemini` … AIを切り替え
- チャットで `AI_Player 鉄を集めて` や `@ai 家を建てて` … AIに指示
- `/aiplayer say <文>` / `/aiplayer status` / `/aiplayer reload` / `/aiplayer despawn`

## AIができること
採掘(mine) / 移動(move, follow, explore) / クラフト(craft) / 精錬(smelt) / ブロック設置(place) / 戦闘(attack) /
装備・食事 / アイテムを渡す(give) / 待機。観測情報（位置・体力・持ち物・周囲の見えているブロックと敵）を
毎ターンAIに渡し、AIがJSONで行動を返します。空腹時の自動食事、防具の自動装備、近くの敵への反撃、死亡時のリスポーンは
AIを待たず自動で行います。

## 仕様上の簡略化
- 移動は物理演算ではなく、A*経路に沿って座標を直接進める方式（壁抜けや落下ダメージはなし）
- 精錬は炉の近くで、燃料と素材をインベントリから消費して短時間待つだけ（炉の中身は使わない）
- `xray: false`（既定）ではAIは空気に面した「見えている」ブロックしか知りません
