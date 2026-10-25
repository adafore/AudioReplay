# Změny

## 1.2.0 — 25. 10. 2026

- Automatické čekání na mikrofon při hovoru, obsazení jinou aplikací nebo dočasném výpadku vstupu; po uvolnění naváže na stávající historii.
- Kontrola dostupnosti při chybě každé 2 sekundy, opětovné otevření ve stejném PCM formátu bez vymazání bufferu. Přerušení se ve zvuku vynechá a je uvedené na obrazovce Záznam.
- Ruční Pozastavit a Zastavit ruší i čekání a další pokusy. Staré události původního vstupu nemohou nahrávání znovu spustit.
- Nastavení hlasitosti ukládaného zvuku 0–18 dB přímo na Záznamu i v Nastavení, předvolby 0/6/12/18 dB.
- Samostatné tlačítko Zesílit nahrávku v Historii pro hlasitější kopii celého souboru, bez nutnosti vybírat ořez. Originál zůstává zachovaný.
- Aktualizované stavy oznámení, widgetu a dlaždice při čekání na mikrofon.

## 1.1.0 — 16. 10. 2026

- Adaptivní ikona bez bílého podkladu v rozích.
- Názvy podle dne: `16.10.2026`, `16.10.2026 2`, …; staré automatické názvy se převedou, vlastní názvy zůstanou.
- Nastavení oznámení přímo dostupné v aplikaci: zobrazení/zvuk, podrobnosti, tlačítka a soukromí. Nahrávání může běžet i s oznámením vypnutým uživatelem v Androidu.
- Widget mění ovládací prvky podle velikosti: předvolený čas, 1 minuta, 5 minut, Spustit/Pokračovat a Zastavit.
- Předvolby bufferu 5/10/20/30/60 minut; úseky 1/2,5/5/10/20/30 minut podle kapacity bufferu.
- Ořez a zesílení 0–18 dB; výsledek se ukládá jako nová kopie.
- Otevření složky z Historie; bezpečné zpřístupnění soukromých nahrávek přes MediaStore.
- Odstraněné experimentální externí ovládání a text o tlačítkách hlasitosti.
- Menší optimalizované instalační APK, zachovaný podpis pro aktualizaci verze 1.0.0.

## 1.0.0 — 15. 10. 2026

První funkční verze: rotující diskový PCM buffer, AAC/M4A export souběžný s nahráváním, české Compose UI, historie, oznámení, dlaždice a widget.
