{
  description = "scala-pds, an AT Protocol Personal Data Server in Scala 3";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      # nixpkgs dropped x86_64-darwin in 26.11; Intel Macs need a 26.05 input.
      systems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];

      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # mise pins Temurin 21; fall back to the newest packaged JDK when a
      # matching version is not in this nixpkgs revision.
      jdkFor = pkgs: pkgs.temurin-bin-21 or pkgs.jdk21 or pkgs.jdk;

      sourceFor =
        pkgs:
        pkgs.lib.cleanSourceWith {
          name = "scala-pds-source";
          src = self;
          filter =
            path: type:
            let
              relative = pkgs.lib.removePrefix (toString self + "/") (toString path);
            in
            builtins.any (root: relative == root || pkgs.lib.hasPrefix (root + "/") relative) [
              "build.sbt"
              "project"
              "src"
            ];
        };
    in
    {
      packages = forAllSystems (
        pkgs:
        let
          jdk = jdkFor pkgs;
          source = sourceFor pkgs;
        in
        rec {
          # Runs the PDS from the store source. Maven dependencies are fetched
          # on first start into $XDG_CACHE_HOME (network required once); they
          # are not vendored into the store.
          scala-pds = pkgs.writeShellApplication {
            name = "scala-pds";
            runtimeInputs = [
              jdk
              pkgs.sbt
            ];
            text = ''
              cache="''${XDG_CACHE_HOME:-$HOME/.cache}/scala-pds"
              mkdir -p "$cache/coursier" "$cache/ivy" "$cache/sbt"
              export COURSIER_CACHE="$cache/coursier"
              export SBT_OPTS="-Dsbt.global.base=$cache/sbt -Divy.home=$cache/ivy ''${SBT_OPTS:-}"

              # The sources live in the read-only store, so the zero-configuration
              # SQLite database and master key go in the invoking directory.
              if [ -z "''${PDS_DATABASE_URL:-}''${PDS_DATABASE_USER:-}''${PDS_DATABASE_PASSWORD:-}" ]; then
                export PDS_SQLITE_PATH="''${PDS_SQLITE_PATH:-$PWD/data/scala-pds.sqlite3}"
              fi

              build="$cache/build"
              mkdir -p "$build"
              cp -r --no-preserve=mode,ownership "${source}/." "$build/"
              cd "$build"
              exec sbt -batch "''${1:-run}"
            '';
          };
          default = scala-pds;
        }
      );

      devShells = forAllSystems (
        pkgs:
        let
          jdk = jdkFor pkgs;
          postgres = pkgs.postgresql_17 or pkgs.postgresql;
        in
        {
          default = pkgs.mkShell {
            name = "scala-pds";
            packages = [
              jdk
              pkgs.sbt
              pkgs.git
              pkgs.nodejs_24
              postgres
              pkgs.deno
            ];
            env.PG_BIN = "${postgres}/bin";
            shellHook = ''
              echo "scala-pds: JDK $(java -version 2>&1 | head -1)"
            '';
          };
        }
      );

      formatter = forAllSystems (pkgs: pkgs.nixfmt or pkgs.nixfmt-rfc-style);
    };
}
