#!/bin/bash
CURRENTDIR=$(basename "$PWD")

MBTJAR="cores/mbt.jar"
SHADERC="cores/shaderc"
BGFX="cores/"
DATAS="cores/datas/1.20.0"
BUILDS="SHADERS"
SHADERM="materials"
LIBRARIES="cores/"
CORES="--compile --shaderc $SHADERC --include $BGFX"

G='\e[1;32m'
GD='\e[0;32m'
Y='\e[1;33m'
YD='\e[0;33m'
RESET='\e[0m'

TARGETS=""
MATERIALS=""
ARG_MODE=""
for t in "$@"; do
  if [ "${t:0:1}" == "-" ]; then
    # mode
    OPT=${t:1}
    if [[ "$OPT" =~ ^[pmt]$ ]]; then
      ARG_MODE=$OPT
    else
      echo "Invalid option: $t"      
      exit 1
    fi
  elif [ "$ARG_MODE" == "p" ]; then
    # target platform
    TARGETS+="$t "
  elif [ "$ARG_MODE" == "m" ]; then
    # material files
    MATERIALS+="$SHADERM/$t"
  elif [ "$ARG_MODE" == "t" ]; then
    # mbt threads
    THREADS="$t"
  fi
  shift
done

if [ -z "$TARGETS" ]; then
  TARGETS="Android"
fi
ORIGINAL="ShaderCompiler"
if [ -z "$MATERIALS" ]; then
  # all materials
  MATERIALS="$SHADERM/*"
fi

if [ -z "$THREADS" ]; then
  # 1 thread per core
  THREADS=$(nproc --all)
fi
CORES+=" --threads $THREADS"

for p in $TARGETS; do
  echo "-----------------------------------------"
  echo -e "$YD>> File:$Y $CURRENTDIR $RESET"
  echo -e "$YD>> Using: $ORIGINAL $RESET"
  echo -e "$GD>> Building: $G$p $(basename "$DATAS")$RESET"
  if [ -d "$DATAS/$p" ]; then
    for s in $MATERIALS; do
    LD_LIBRARY_PATH=$LIBRARIES java -jar $MBTJAR $CORES --output $BUILDS/$p --data $DATAS/$p/${s##*/} $s -m
    echo -e "$G>> Success. $RESET"
    echo "-----------------------------------------"
    done
  else
    echo "Error: $DATAS/$p not found"
  fi
done