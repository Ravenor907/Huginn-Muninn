#!/bin/sh
# Fetches Huginn's fonts (SIL Open Font License) into fonts/. CI runs this before every build.
# Every file comes from one fixed commit of github.com/google/fonts and must match its SHA-256, so the build never
# takes a font that changed upstream. To move to newer fonts: change the commit, then every hash below.
set -eu
cd "$(dirname "$0")/../../huginn"
mkdir -p fonts
commit=6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec
base=https://raw.githubusercontent.com/google/fonts/$commit/ofl
get() {   # get <path under ofl/> <file name> <sha256>
  curl -sSfL --retry 3 -o "fonts/$2" "$base/$1"
  got=$(sha256sum "fonts/$2" | cut -d' ' -f1)
  if [ "$got" != "$3" ]; then
    rm -f "fonts/$2"
    echo "::error title=Font changed::$2 has SHA-256 $got, not $3"
    exit 1
  fi
}
get "cinzel/Cinzel%5Bwght%5D.ttf" Cinzel.ttf f4d83d34d1f6c741193e4acf4b3dff9531e5a67b6aa65228d00a7db72a4e0f34
get "cinzel/OFL.txt" OFL-Cinzel.txt f2b3029aba64c378bf0963b62945eee15e564fe4330b934c8f2eb058282b5e83
get "barlow/Barlow-Regular.ttf" Barlow-Regular.ttf 95aa02c7c43096e0dd44d787ba6216864a67157e402adab59b35572e0c1577ea
get "barlow/Barlow-Medium.ttf" Barlow-Medium.ttf f8906f762cb73dca441da034bc363b2d8e2e68bc10d5c05e58717646c20cc4b4
get "barlow/Barlow-SemiBold.ttf" Barlow-SemiBold.ttf 86577cb32f8abe3673db53ca0f4221e6856751a4f6730c867e00f720f8bb1fc5
get "barlow/Barlow-Bold.ttf" Barlow-Bold.ttf 84e6a4d61e7c3e21f3c50ea6a4f7e5303a3467864c038be6ea3759bab8d547f9
get "barlow/OFL.txt" OFL-Barlow.txt 186d750eb496a4c17a76385f82be6aea2ac1cf2de074a811d63786cf374ea73f
get "notosansrunic/NotoSansRunic-Regular.ttf" NotoSansRunic-Regular.ttf 615b6c1166a8c51816dc1536784acbf486547e3bed03dbfdd70910e10066793b
get "notosansrunic/OFL.txt" OFL-NotoSansRunic.txt b0fbcb9eaf0f5f415358bfefa5999ee75e18cde4a275b78285b2140a9e381865
ls fonts
