-- =====================================================================
-- nuam · Mantenedor de Calificaciones Tributarias  (PostgreSQL 14+)
-- Basado en el modelo relacional del diagrama "BaseDeDatosMR".
-- Tablas del diagrama: corredora, usuario, instrumento, calificacion, factor,
-- comentario, carga_masiva, carga_masiva_detalle, auditoria.
-- Tablas añadidas para cubrir las historias de usuario: periodo, mercado_formula,
-- calificacion_version, conciliacion, notificacion, respaldo, api_token, dispositivo.
-- =====================================================================

CREATE TABLE IF NOT EXISTS corredora (
  corredora_id SERIAL PRIMARY KEY,
  nombre       VARCHAR(120) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS usuario (
  usuario_id        SERIAL PRIMARY KEY,
  corredora_id      INT REFERENCES corredora(corredora_id),
  nombre            VARCHAR(120) NOT NULL,
  correo            VARCHAR(160) NOT NULL UNIQUE,
  -- HU-8.5: un usuario sin rol (NULL) no puede iniciar sesión
  rol               VARCHAR(20) CHECK (rol IN ('CORREDOR','SUPERVISOR','ADMIN','AUDITOR')),
  activo            BOOLEAN NOT NULL DEFAULT TRUE,
  password_hash     VARCHAR(200) NOT NULL,
  totp_secret       VARCHAR(64),                 -- HU-12 (2FA TOTP)
  totp_activo       BOOLEAN NOT NULL DEFAULT FALSE,
  intentos_fallidos INT NOT NULL DEFAULT 0,
  bloqueado_hasta   TIMESTAMPTZ,
  ultimo_login      TIMESTAMPTZ,
  creado_en         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dispositivo (        -- HU-12.5 dispositivos reconocidos
  dispositivo_id SERIAL PRIMARY KEY,
  usuario_id     INT NOT NULL REFERENCES usuario(usuario_id),
  huella         CHAR(64) NOT NULL,
  primera_vez    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (usuario_id, huella)
);

CREATE TABLE IF NOT EXISTS api_token (          -- HU-19 autenticación de la API
  token_id    SERIAL PRIMARY KEY,
  usuario_id  INT NOT NULL REFERENCES usuario(usuario_id),
  nombre      VARCHAR(80) NOT NULL,
  token_hash  CHAR(64) NOT NULL UNIQUE,         -- solo se guarda el hash SHA-256
  activo      BOOLEAN NOT NULL DEFAULT TRUE,
  creado_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
  ultimo_uso  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS instrumento (
  instrumento_id SERIAL PRIMARY KEY,
  nombre         VARCHAR(160) NOT NULL,
  nombre_norm    VARCHAR(160) NOT NULL,         -- minúsculas y sin acentos (HU-14.5)
  mercado        CHAR(2) NOT NULL CHECK (mercado IN ('CL','PE','CO')),   -- HU-16
  UNIQUE (nombre_norm, mercado)
);

-- HU-16.3: fórmula de cálculo parametrizable por país
-- factor_i = monto_i / SUM(monto[base_desde..base_hasta]), truncado a "decimales"
CREATE TABLE IF NOT EXISTS mercado_formula (
  mercado     CHAR(2) PRIMARY KEY CHECK (mercado IN ('CL','PE','CO')),
  base_desde  INT NOT NULL,
  base_hasta  INT NOT NULL,
  decimales   INT NOT NULL,
  descripcion TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS periodo (            -- HU-7 / HU-18
  periodo_id          SERIAL PRIMARY KEY,
  corredora_id        INT NOT NULL REFERENCES corredora(corredora_id),
  periodo_comercial   VARCHAR(4) NOT NULL,
  estado              VARCHAR(12) NOT NULL DEFAULT 'ABIERTO'
                      CHECK (estado IN ('ABIERTO','EN_REVISION','CERRADO')),
  fecha_limite_dj1948 DATE NOT NULL,
  fecha_cierre        TIMESTAMPTZ,
  UNIQUE (corredora_id, periodo_comercial)
);

CREATE TABLE IF NOT EXISTS calificacion (
  calificacion_id    SERIAL PRIMARY KEY,
  instrumento_id     INT NOT NULL REFERENCES instrumento(instrumento_id),
  corredora_id       INT REFERENCES corredora(corredora_id),   -- NULL = dato global (bolsa/sistema)
  usuario_id         INT REFERENCES usuario(usuario_id),
  secuencia          INT NOT NULL,
  ejercicio          INT NOT NULL,
  periodo_comercial  VARCHAR(4) NOT NULL,
  fecha_pago         DATE,
  descripcion        VARCHAR(400),
  origen             VARCHAR(10) NOT NULL CHECK (origen IN ('sistema','bolsa','corredor')),
  acogido_isfut      BOOLEAN NOT NULL DEFAULT FALSE,
  estado             VARCHAR(20) NOT NULL DEFAULT 'INCOMPLETA'
                     CHECK (estado IN ('INCOMPLETA','COMPLETA','PENDIENTE_REVISION','ERROR','RECHAZADA','APROBADA')),
  motivo_error       TEXT,
  eliminada          BOOLEAN NOT NULL DEFAULT FALSE,           -- baja lógica: se conserva todo el historial
  fecha_creacion     TIMESTAMPTZ NOT NULL DEFAULT now(),
  fecha_modificacion TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_calif_scope   ON calificacion (corredora_id, periodo_comercial) WHERE NOT eliminada;
CREATE INDEX IF NOT EXISTS ix_calif_instr   ON calificacion (instrumento_id, secuencia);

CREATE TABLE IF NOT EXISTS factor (
  factor_id       SERIAL PRIMARY KEY,
  calificacion_id INT NOT NULL REFERENCES calificacion(calificacion_id),
  numero_factor   INT NOT NULL CHECK (numero_factor BETWEEN 8 AND 36),   -- 29 factores
  monto           NUMERIC(20,4),
  valor           NUMERIC(16,10),
  UNIQUE (calificacion_id, numero_factor)
);

CREATE TABLE IF NOT EXISTS calificacion_version (   -- HU-13
  version_id      SERIAL PRIMARY KEY,
  calificacion_id INT NOT NULL REFERENCES calificacion(calificacion_id),
  numero_version  INT NOT NULL,
  accion          VARCHAR(20) NOT NULL,
  usuario_id      INT REFERENCES usuario(usuario_id),
  snapshot        TEXT NOT NULL,
  fecha           TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (calificacion_id, numero_version)
);

CREATE TABLE IF NOT EXISTS comentario (
  comentario_id   SERIAL PRIMARY KEY,
  calificacion_id INT NOT NULL REFERENCES calificacion(calificacion_id),
  usuario_id      INT REFERENCES usuario(usuario_id),
  texto           TEXT NOT NULL,
  fecha           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS conciliacion (           -- HU-17
  conciliacion_id   SERIAL PRIMARY KEY,
  calificacion_id   INT NOT NULL REFERENCES calificacion(calificacion_id),
  usuario_id        INT REFERENCES usuario(usuario_id),
  montos_certificado TEXT NOT NULL,
  montos_dj          TEXT NOT NULL,
  diferencias        TEXT,
  estado             VARCHAR(12) NOT NULL CHECK (estado IN ('COINCIDE','DISCREPANCIA','RESUELTA')),
  fuente_usada       VARCHAR(12),
  motivo             TEXT,
  fecha              TIMESTAMPTZ NOT NULL DEFAULT now(),
  fecha_resolucion   TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS carga_masiva (
  carga_masiva_id   SERIAL PRIMARY KEY,
  usuario_id        INT NOT NULL REFERENCES usuario(usuario_id),
  tipo              VARCHAR(10) NOT NULL,            -- CSV | XLSX | SII
  nombre_archivo    VARCHAR(200) NOT NULL,
  ruta_archivo      VARCHAR(400),
  estado            VARCHAR(30) NOT NULL,
  periodo_comercial VARCHAR(4),
  total_filas       INT NOT NULL DEFAULT 0,
  filas_ok          INT NOT NULL DEFAULT 0,
  filas_error       INT NOT NULL DEFAULT 0,
  fecha             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS carga_masiva_detalle (
  cmd_id          SERIAL PRIMARY KEY,
  carga_masiva_id INT NOT NULL REFERENCES carga_masiva(carga_masiva_id),
  instrumento_id  INT REFERENCES instrumento(instrumento_id),
  fila            INT NOT NULL,
  secuencia       INT,
  estado          VARCHAR(10) NOT NULL,
  motivo_error    TEXT
);

CREATE TABLE IF NOT EXISTS auditoria (               -- HU-5 (solo INSERT, ver triggers)
  auditoria_id      BIGSERIAL PRIMARY KEY,
  usuario_id        INT REFERENCES usuario(usuario_id),
  accion            VARCHAR(40) NOT NULL,
  entidad_afectada  VARCHAR(40),
  registro_id       INT,
  detalle           TEXT,
  origen            VARCHAR(10) NOT NULL DEFAULT 'WEB',   -- WEB | API | SII | MASIVA | SISTEMA
  fecha             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_auditoria_fecha ON auditoria (fecha DESC);

CREATE TABLE IF NOT EXISTS notificacion (            -- HU-7
  notificacion_id   SERIAL PRIMARY KEY,
  usuario_id        INT NOT NULL REFERENCES usuario(usuario_id),
  tipo              VARCHAR(20) NOT NULL,
  mensaje           TEXT NOT NULL,
  pendientes        INT NOT NULL DEFAULT 0,
  periodo_comercial VARCHAR(4),
  url               VARCHAR(300),
  leida             BOOLEAN NOT NULL DEFAULT FALSE,
  dia               DATE NOT NULL DEFAULT current_date,
  fecha             TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (usuario_id, tipo, periodo_comercial, dia)
);

CREATE TABLE IF NOT EXISTS respaldo (                -- HU-11
  respaldo_id  SERIAL PRIMARY KEY,
  archivo      VARCHAR(300) NOT NULL,
  estado       VARCHAR(10) NOT NULL CHECK (estado IN ('EN_CURSO','OK','ERROR','PURGADO')),
  tipo         VARCHAR(10) NOT NULL DEFAULT 'AUTO',
  iniciado     TIMESTAMPTZ NOT NULL DEFAULT now(),
  finalizado   TIMESTAMPTZ,
  bytes        BIGINT,
  mensaje      TEXT
);

-- ---------------------------------------------------------------------
-- RNF-03 · Inmutabilidad: ni el administrador puede editar/borrar el log
-- ni el historial de versiones (se rechaza a nivel de base de datos).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bloquear_modificacion() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'La tabla % es de solo lectura (inmutable)', TG_TABLE_NAME
    USING ERRCODE = 'insufficient_privilege';
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_auditoria_ro ON auditoria;
CREATE TRIGGER trg_auditoria_ro BEFORE UPDATE OR DELETE ON auditoria
  FOR EACH ROW EXECUTE FUNCTION bloquear_modificacion();
DROP TRIGGER IF EXISTS trg_auditoria_tr ON auditoria;
CREATE TRIGGER trg_auditoria_tr BEFORE TRUNCATE ON auditoria
  FOR EACH STATEMENT EXECUTE FUNCTION bloquear_modificacion();

DROP TRIGGER IF EXISTS trg_version_ro ON calificacion_version;
CREATE TRIGGER trg_version_ro BEFORE UPDATE OR DELETE ON calificacion_version
  FOR EACH ROW EXECUTE FUNCTION bloquear_modificacion();
DROP TRIGGER IF EXISTS trg_version_tr ON calificacion_version;
CREATE TRIGGER trg_version_tr BEFORE TRUNCATE ON calificacion_version
  FOR EACH STATEMENT EXECUTE FUNCTION bloquear_modificacion();

-- Datos de referencia: fórmulas por país (HU-16.3)
ALTER TABLE calificacion ADD COLUMN IF NOT EXISTS tipo_dj VARCHAR(4) NOT NULL DEFAULT '1948' CHECK (tipo_dj IN ('1948','1922'));

INSERT INTO mercado_formula (mercado, base_desde, base_hasta, decimales, descripcion) VALUES
 ('CL', 8, 19, 8, 'Chile (SII): factor = monto / suma de montos 8..19, 8 decimales'),
 ('PE', 8, 36, 8, 'Perú: factor = monto / suma de todos los montos 8..36, 8 decimales'),
 ('CO', 8, 19, 6, 'Colombia: factor = monto / suma de montos 8..19, 6 decimales')
ON CONFLICT (mercado) DO NOTHING;
