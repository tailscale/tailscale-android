{
  description = "Tailscale Android build environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      supportedSystems = [ "x86_64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs supportedSystems (system: f system);
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };

          commonPackages = with pkgs; [
            bash
            cacert
            curl
            git
            gnumake
            gzip
            jdk21
            unzip
            zip
          ];

          linuxPackages = with pkgs; [
            gcc
            glibc
            libGL
            libx11
            libxcursor
            libxfixes
            libxkbcommon
            pkg-config
            stdenv.cc.cc.lib
            wayland
            zlib
          ];

          shellProfile = ''
            export JAVA_HOME="${pkgs.jdk21.home}"
            export ANDROID_SDK_ROOT="''${ANDROID_SDK_ROOT:-$PWD/android-sdk}"
            export ANDROID_HOME="''${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
            export PATH="$PWD/tool:$PWD/android/build/go/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

            export GOTOOLCHAIN=local
            export TS_USE_TOOLCHAIN=1

            if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
              echo "Android SDK not found at $ANDROID_HOME."
              echo "Run: make androidsdk"
            fi
          '';
        in
        {
          default = pkgs.mkShell {
            packages = commonPackages ++ pkgs.lib.optionals pkgs.stdenv.isLinux linuxPackages;
            shellHook = shellProfile;
          };
        });
    };
}
