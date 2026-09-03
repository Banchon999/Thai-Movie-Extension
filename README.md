# Cloudstream · 25-HD ภาษาไทย

**สถานะ: ซอร์สเวอร์ชันทดลอง ยังไม่ยืนยันว่าเล่นกับเว็บจริงได้**

โปรเจกต์ส่วนขยายสำหรับ `https://25-hd.com/` ชื่อในแอปคือ **25-HD (ทดลอง)**
นี่คือซอร์สโปรเจกต์ Kotlin ไม่ใช่ APK และ ZIP ชุดนี้ไม่ใช่ไฟล์ `.cs3` ที่ติดตั้งได้ทันที

## สิ่งที่เขียนไว้

- หน้าอัปเดตล่าสุด พร้อมตามลิงก์หน้าถัดไปที่พบใน HTML
- ค้นหาภาษาไทยผ่านรูปแบบ WordPress `/?s=คำค้น`
- อ่านชื่อเรื่อง รูปปก เรื่องย่อ ปี และหมวดหมู่
- อ่านตอนซีรีส์จากรายการตอนและเรียงตามเลขตอน หากพบเลข
- อ่าน `<video>`, `<source>`, iframe ภายในตัวเล่น และลิงก์ MP4/HLS จาก player configuration
- รองรับข้อมูล DooPlay AJAX เมื่อมี `data-post`, `data-nume`, `data-type` อยู่ในหน้า
- ส่งลิงก์ embed ให้ `loadExtractor()` ของ Cloudstream และรองรับไฟล์คำบรรยายจาก `<track>`
- จำกัดจำนวนหน้าตัวเล่นและความลึก ป้องกัน iframe วนซ้ำ
- GitHub Actions สำหรับทดสอบ บิลด์ `.cs3`/`.jar` และเผยแพร่ `repo.json`

**ฟีเจอร์ข้างบนคือเส้นทางที่โค้ดรองรับ ไม่ใช่รายการที่ผ่านการทดสอบกับ 25-HD แล้ว**

## ข้อจำกัดที่ต้องรู้

ตรวจเมื่อ 3 กันยายน 2026: การเปิดเว็บไซต์จากสภาพแวดล้อมพัฒนาตอบ HTTP 403
จึงยังไม่ได้ตรวจ HTML หน้าแรก หน้าค้นหา หน้าหนัง หน้าซีรีส์ หรือตัวเล่นจริง
ตัวเลือก CSS และรูปแบบ DooPlay ใน `SiteParser.kt` เป็นสมมติฐานสำหรับ WordPress ที่ต้องปรับจาก HTML จริง
ไม่มีการอ้างว่าตัวเล่นของเว็บนี้เป็น DooPlay แน่นอน

สภาพแวดล้อมนี้ไม่มี Gradle/Android SDK พร้อมใช้ และการดาวน์โหลดเครื่องมือบิลด์ไม่สำเร็จ
จึงใช้ GitHub Actions เป็นสภาพแวดล้อมบิลด์ ดู `TEST_REPORT.md` และหน้า Actions สำหรับผลล่าสุด

ตัวเล่นที่สร้าง URL ด้วย JavaScript, ต้องมี session เฉพาะ, ใช้ API/token เฉพาะเว็บ หรือไม่มี extractor ใน Cloudstream อาจยังเล่นไม่ได้
ระบบนี้ไม่แก้ CAPTCHA เอง และไม่มีการถอด DRM
บางเรื่องที่หน้าไพ่ไม่มีข้อมูลว่าเป็นซีรีส์อาจแสดงเป็นหนังในหน้าค้นหา ก่อนแก้ประเภทเมื่อเปิดรายละเอียด
ผลค้นหาปัจจุบันอ่านหน้าแรก ส่วนหน้าอัปเดตล่าสุดรองรับการโหลดหน้าถัดไป

## วิธีใช้ GitHub ให้บิลด์

1. เปิด repository https://github.com/Banchon999/Thai-Movie-Extension
2. หาก fork ไปใช้เอง ให้อนุญาต GitHub Actions ใน repo ของคุณ
3. เปิดแท็บ **Actions → Build 25-HD**
4. Workflow จะรันการทดสอบ Kotlin และ Python ก่อนบิลด์ หากขั้นตอนไหนล้มเหลวจะไม่เผยแพร่
5. เมื่อทั้ง `build` และ `publish` ผ่าน จะมี branch `builds` ที่มี `TwentyFiveHD.cs3`, `TwentyFiveHD.jar`, `plugins.json` และ `repo.json`
6. ใช้ **Settings → Extensions → Add repository** ใน Cloudstream ใส่ URL รูปแบบนี้ โดยเปลี่ยน OWNER และ REPO เป็นของจริง:

   ```text
   https://raw.githubusercontent.com/OWNER/REPO/builds/repo.json
   ```

7. เปิด repo ที่เพิ่ม แล้วติดตั้ง **TwentyFiveHD** และเลือกผู้ให้บริการ **25-HD (ทดลอง)**

ลิงก์สำหรับ repo นี้หลัง workflow เผยแพร่สำเร็จ:
`https://raw.githubusercontent.com/Banchon999/Thai-Movie-Extension/builds/repo.json`
Workflow ใช้ `GITHUB_REPOSITORY` สร้างลิงก์ให้ตรงกับ repo จริงโดยอัตโนมัติ ไม่ต้องแก้ Python
ไม่ต้องสร้าง branch `builds` ด้วยมือ และการอัปเดตไม่ใช้ force push
หาก repository มีข้อจำกัด Actions ให้เปิดใช้งาน Actions และอนุญาต job เผยแพร่เขียน contents

## บิลด์บนคอม

ต้องมี **JDK 17, Gradle 8.12, Android SDK platform 35 และ build-tools 35.0.0**
ตั้ง `ANDROID_HOME`/`ANDROID_SDK_ROOT` หรือใส่ `sdk.dir` ใน `local.properties`
ใช้ Gradle ที่ติดตั้งไว้โดยตรง ชุดนี้ไม่มี Gradle wrapper JAR

```bash
gradle --no-daemon TwentyFiveHD:testDebugUnitTest make makePluginsJson ensureJarCompatibility
python3 -m unittest discover -s tests -v
python3 scripts/stage_release.py --repository OWNER/REPO
```

ก่อนบิลด์ local ให้ตั้ง `GITHUB_REPOSITORY=OWNER/REPO` ด้วย เพื่อให้ metadata ตรงกับ repo ของคุณ
หากไม่ตั้ง โค้ดจะใช้ `Banchon999/Thai-Movie-Extension` เป็นค่าเริ่มต้นที่เสนอไว้
ไฟล์ปลั๊กอินจะอยู่ที่ `TwentyFiveHD/build/TwentyFiveHD.cs3` และชุดพร้อมเผยแพร่อยู่ที่ `dist/`
ถ้าใช้ Windows ให้ใช้ `python` แทน `python3` ได้

ใช้ dependency/API ตามแนวทางล่าสุดของ upstream ที่ตรวจพบ โดย `-SNAPSHOT` อาจเปลี่ยนภายหลัง
หากใช้ Cloudstream รุ่นเก่าที่ไม่รู้จัก `BasePlugin` ต้องอัปเดตแอปหรือปรับฐาน API ให้ตรงรุ่น

## ทดสอบกับเว็บไซต์จริงก่อนเปลี่ยนเป็น Stable

- หน้าแรก: ต้องมีรายการและรูปปก ไม่ใช่หน้าตรวจเบราว์เซอร์
- ค้นหาชื่อไทยที่มีอยู่จริง และชื่อที่ไม่มีจริง
- หนัง: ต้องเปิดรายละเอียดและเล่นได้อย่างน้อยหนึ่งเรื่อง
- ซีรีส์: ต้องเห็นรายการตอนและเล่นได้อย่างน้อยสองตอนติดกัน
- ทดลองพากย์ไทย/ซับไทยและเซิร์ฟเวอร์สำรองตามที่เว็บมี
- เปลี่ยนเครือข่ายแล้วลองเล่นใหม่ เพื่อเช็ก token/referer/session

เมื่อเล่นไม่ได้ ส่ง URL เรื่อง/ตอนที่เจอปัญหา ข้อความ error และเวอร์ชัน Cloudstream มา
หากยังเข้าถึงเว็บจากเครื่องพัฒนาไม่ได้ ให้แนบ HTML หน้าแรก หน้าค้นหา และหน้าตัวเล่นจากเบราว์เซอร์ที่เปิดได้
สามารถใช้ DevTools → Elements → คัดลอก outerHTML ของส่วนรายการ/ตัวเล่น โดยลบข้อมูลส่วนตัวก่อนส่ง
ไม่จำเป็นต้องส่งรหัสผ่านหรือ cookie

จุดแก้โครงสร้างเว็บอยู่ที่ `TwentyFiveHD/src/main/kotlin/com/demos/hd25/SiteParser.kt`
จุดแก้การเรียก API/ตัวเล่นอยู่ที่ `TwentyFiveHDProvider.kt`
เพิ่ม HTML จริงที่ย่อเหลือเฉพาะส่วนจำเป็นลงใน tests ก่อนเปลี่ยน selectors

## แหล่งอ้างอิงด้านเทคนิค

- https://github.com/recloudstream/extensions — โครงสร้าง Gradle, BasePlugin และการเผยแพร่
- https://github.com/recloudstream/cloudstream/blob/master/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt
- https://github.com/recloudstream/cloudstream/blob/master/library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt
- https://github.com/recloudstream/gradle — รูปแบบ plugin metadata และไฟล์ CS3

อ้างอิง API จากต้นทางโดยตรง ไม่ได้รวมสื่อวิดีโอไว้ในโปรเจกต์
