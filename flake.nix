{
  description = "Tailscale build environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    android.url = "github:tadfisher/android-nixpkgs";
    android.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, android }:
    let
      supportedSystems = [ "x86_64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs supportedSystems (system: f system);
    in
    {
      devShells = forAllSystems
        (system:
          let
            pkgs = import nixpkgs {
              inherit system;
            };
            android-sdk = android.sdk.${system} (sdkPkgs: with sdkPkgs;
              [
                build-tools-30-0-2
                cmdline-tools-latest
                platform-tools
                platforms-android-31
                platforms-android-30
                ndk-23-1-7779620
                patcher-v4
              ]);
          in
          {
            default = (with pkgs; buildFHSUserEnv {
              name = "tailscale";
              profile = ''
                export ANDROID_SDK_ROOT="${android-sdk}/share/android-sdk"
                export JAVA_HOME="${jdk8.home}"
              '';
              targetPkgs = pkgs: with pkgs; [
                android-sdk
                jdk8
                clang
              ] ++ (if stdenv.isLinux then [
                vulkan-headers
                libxkbcommon
                wayland
                xorg.libX11
                xorg.libXcursor
                xorg.libXfixes
                libGL
                pkgconfig
              ] else [ ]);
            }).env;
          }
        );
    };
}
