#!/usr/bin/env python3
"""Gera os ícones do Orbion (adaptativo e tradicional) a partir do emblema.

Uso: python3 scripts/gerar-icone.py [design/icone-orbion.png]

A imagem de origem é o emblema redondo já recortado (fundo transparente).
O PNG guarda, nas partes transparentes, o brilho dourado original; ele é
reaproveitado na camada de fundo.

Ícone adaptativo (Android 8+; o Orbion exige Android 8, então é o que todo
aparelho usa): duas camadas de 108 dp. Só os 72 dp do meio aparecem, cortados
no formato do celular (redondo, quadrado arredondado, "squircle"...), e só o
círculo de 66 dp do meio é garantido em qualquer formato.
- Frente: o emblema com EMBLEMA_DP de diâmetro, transparente em volta.
- Fundo: o brilho dourado da imagem original, puxado para perto do emblema
  (assim a borda que aparece em volta dele é dourada, como na arte).
- Monocromático (Android 13+, ícones com tema): é um desenho vetorial à parte,
  res/drawable/ic_launcher_monochrome.xml.
"""
import math
import pathlib
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

RAIZ = pathlib.Path(__file__).resolve().parent.parent
RES = RAIZ / "android" / "app" / "src" / "main" / "res"
DESIGN = RAIZ / "design"
DENSIDADES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

CAMADA_DP = 108       # camada do ícone adaptativo
VISIVEL_DP = 72       # parte que aparece
EMBLEMA_DP = 68       # diâmetro do emblema (zona segura: 66; visível: 72)
LEGADO_DP = 48        # ícone tradicional
GRANDE = 1024         # trabalha em alta resolução e reduz no fim


def reduzir(im: Image.Image, lado: int) -> Image.Image:
    """Reduz sem franja escura na borda transparente (alfa pré-multiplicado)."""
    return im.convert("RGBa").resize((lado, lado), Image.LANCZOS).convert("RGBA")


def achar_circulo(im: Image.Image) -> tuple[float, float, float]:
    """Centro e raio do emblema, pela parte opaca da imagem."""
    alfa = np.asarray(im.getchannel("A"))
    ys, xs = np.nonzero(alfa >= 250)
    esquerda, direita, topo, base = xs.min(), xs.max(), ys.min(), ys.max()
    cx, cy = (esquerda + direita + 1) / 2, (topo + base + 1) / 2
    raio = max(direita - esquerda + 1, base - topo + 1) / 2
    return cx, cy, raio


def emblema_recortado(im: Image.Image, cx: float, cy: float, raio: float) -> Image.Image:
    """O emblema num quadrado justo, com o alfa limpo (o recorte deixou ruído 249-254 por dentro)."""
    r = raio + 3  # inclui a borda suavizada
    caixa = (round(cx - r), round(cy - r), round(cx + r), round(cy + r))
    e = im.crop(caixa)
    alfa = e.getchannel("A").point(lambda v: 255 if v >= 240 else v)
    e.putalpha(alfa)
    return e


def frente(emblema: Image.Image) -> Image.Image:
    lado_emblema = round(GRANDE * EMBLEMA_DP / CAMADA_DP)
    camada = Image.new("RGBA", (GRANDE, GRANDE), (0, 0, 0, 0))
    e = reduzir(emblema, lado_emblema)
    pos = (GRANDE - lado_emblema) // 2
    camada.alpha_composite(e, (pos, pos))
    return camada


def fundo(original: Image.Image, cx: float, cy: float, raio: float) -> Image.Image:
    """Brilho dourado da arte original, puxado para perto do emblema.

    Cada ponto do fundo, na distância ρ do centro, pega a cor da imagem
    original no mesmo ângulo, na distância R = raio + (ρ - borda) * k — a
    borda do emblema novo encosta na borda do emblema original, e a luz vem
    do mesmo lado (de cima).
    """
    rgb = np.asarray(original.convert("RGB"), dtype=np.float32)
    alt, larg = rgb.shape[:2]
    por_dp = GRANDE / CAMADA_DP
    v, u = np.mgrid[0:GRANDE, 0:GRANDE].astype(np.float32)
    x_dp = (u + 0.5) / por_dp - CAMADA_DP / 2
    y_dp = (v + 0.5) / por_dp - CAMADA_DP / 2
    rho = np.hypot(x_dp, y_dp)
    theta = np.arctan2(y_dp, x_dp)
    borda_dp = EMBLEMA_DP / 2 - 1.0
    r0 = raio + 4.0
    k = 3.0  # px da arte original por dp do fundo
    R = r0 + np.clip(rho - borda_dp, 0, None) * k
    # Não sai da imagem original: limita pela distância até a borda dela.
    cos, sen = np.cos(theta), np.sin(theta)
    with np.errstate(divide="ignore", invalid="ignore"):
        lim_x = np.where(cos > 0, (larg - 2 - cx) / cos, np.where(cos < 0, (1 - cx) / cos, np.inf))
        lim_y = np.where(sen > 0, (alt - 2 - cy) / sen, np.where(sen < 0, (1 - cy) / sen, np.inf))
    R = np.minimum(R, np.minimum(lim_x, lim_y))
    sx = cx + R * cos
    sy = cy + R * sen
    # Interpolação bilinear.
    x0 = np.clip(np.floor(sx).astype(int), 0, larg - 2)
    y0 = np.clip(np.floor(sy).astype(int), 0, alt - 2)
    fx = (sx - x0)[..., None]
    fy = (sy - y0)[..., None]
    c = (rgb[y0, x0] * (1 - fx) * (1 - fy) + rgb[y0, x0 + 1] * fx * (1 - fy)
         + rgb[y0 + 1, x0] * (1 - fx) * fy + rgb[y0 + 1, x0 + 1] * fx * fy)
    camada = Image.fromarray(np.clip(c, 0, 255).astype(np.uint8), "RGB")
    return camada.filter(ImageFilter.GaussianBlur(3)).convert("RGBA")


def composicao(camada_fundo: Image.Image, camada_frente: Image.Image) -> Image.Image:
    c = camada_fundo.copy()
    c.alpha_composite(camada_frente)
    return c


def mascara(lado: int, forma: str) -> Image.Image:
    m = Image.new("L", (lado * 4, lado * 4), 0)
    d = ImageDraw.Draw(m)
    L = lado * 4
    if forma == "redondo":
        d.ellipse((0, 0, L - 1, L - 1), fill=255)
    elif forma == "arredondado":
        d.rounded_rectangle((0, 0, L - 1, L - 1), radius=round(L * 0.22), fill=255)
    elif forma == "squircle":
        n = 4.0
        pts = []
        for i in range(720):
            t = 2 * math.pi * i / 720
            c, s = math.cos(t), math.sin(t)
            x = math.copysign(abs(c) ** (2 / n), c)
            y = math.copysign(abs(s) ** (2 / n), s)
            pts.append(((x + 1) / 2 * (L - 1), (y + 1) / 2 * (L - 1)))
        d.polygon(pts, fill=255)
    elif forma == "gota":
        d.ellipse((0, 0, L - 1, L - 1), fill=255)
        d.rectangle((L // 2, L // 2, L - 1, L - 1), fill=255)
    return m.resize((lado, lado), Image.LANCZOS)


def recorte_visivel(c: Image.Image) -> Image.Image:
    """Só os 72 dp do meio (o que aparece na tela)."""
    por_dp = GRANDE / CAMADA_DP
    m = (CAMADA_DP - VISIVEL_DP) / 2 * por_dp
    return c.crop((round(m), round(m), round(GRANDE - m), round(GRANDE - m)))


def com_forma(c: Image.Image, forma: str) -> Image.Image:
    v = recorte_visivel(c)
    lado = v.size[0]
    out = Image.new("RGBA", (lado, lado), (0, 0, 0, 0))
    out.paste(v, (0, 0), mascara(lado, forma))
    return out


ADAPTATIVO = """<?xml version="1.0" encoding="utf-8"?>
<!-- Gerado por scripts/gerar-icone.py a partir de design/icone-orbion.png -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
"""


def main() -> None:
    origem = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DESIGN / "icone-orbion.png"
    original = Image.open(origem).convert("RGBA")
    cx, cy, raio = achar_circulo(original)
    print(f"emblema: centro ({cx:.1f}, {cy:.1f}), raio {raio:.1f} px")

    emblema = emblema_recortado(original, cx, cy, raio)
    camada_frente = frente(emblema)
    camada_fundo = fundo(original, cx, cy, raio)
    junto = composicao(camada_fundo, camada_frente)

    for nome, escala in DENSIDADES.items():
        pasta = RES / f"mipmap-{nome}"
        pasta.mkdir(parents=True, exist_ok=True)
        lado_camada = round(CAMADA_DP * escala)
        lado_legado = round(LEGADO_DP * escala)
        reduzir(camada_frente, lado_camada).save(pasta / "ic_launcher_foreground.png", optimize=True)
        camada_fundo.convert("RGB").resize((lado_camada, lado_camada), Image.LANCZOS).save(
            pasta / "ic_launcher_background.png", optimize=True)
        reduzir(com_forma(junto, "arredondado"), lado_legado).save(pasta / "ic_launcher.png", optimize=True)
        reduzir(com_forma(junto, "redondo"), lado_legado).save(pasta / "ic_launcher_round.png", optimize=True)

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for nome in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / nome).write_text(ADAPTATIVO, encoding="utf-8")

    # Ícone grande (site, loja): 512 px, quadrado arredondado.
    DESIGN.mkdir(exist_ok=True)
    reduzir(com_forma(junto, "arredondado"), 512).save(DESIGN / "icone-orbion-512.png", optimize=True)

    # Prévia: como fica em cada formato de celular, grande e no tamanho real.
    formas = ["redondo", "squircle", "arredondado", "gota"]
    previa = Image.new("RGBA", (4 * 280 + 40, 280 + 120), (28, 32, 40, 255))
    d = ImageDraw.Draw(previa)
    for i, forma in enumerate(formas):
        icone = reduzir(com_forma(junto, forma), 256)
        previa.alpha_composite(icone, (20 + i * 280 + 12, 20))
        pequeno = reduzir(com_forma(junto, forma), 56)
        previa.alpha_composite(pequeno, (20 + i * 280 + 12 + 100, 300))
        d.text((20 + i * 280 + 12, 372), forma, fill=(220, 220, 220, 255))
    previa.save(DESIGN / "previa-icone.png")
    print("ícones gerados em", RES)


if __name__ == "__main__":
    main()
